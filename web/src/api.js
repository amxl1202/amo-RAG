/**
 * 与后端打交道的唯一入口。
 *
 * 三件事集中在这里做,否则会散落到每个视图里:
 * 1. 带上 Authorization 头;
 * 2. 把 { code, message, data } 拆成"要么返回 data,要么抛错";
 * 3. 401 时清掉会话并通知外层跳登录页。
 *
 * 令牌放在 localStorage:刷新页面不掉登录态。代价是 XSS 能读到它 ——
 * 所以答案渲染必须过 DOMPurify(见 ChatView),把注入面收敛掉。
 * 换到内存存储可以让 XSS 读不到,但每次刷新都要重新登录,对演示形态不划算。
 */

const TOKEN_KEY = 'amo-rag.token'
const USER_KEY = 'amo-rag.user'

export class ApiError extends Error {
  constructor(message, status) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

let sessionExpiredHandler = () => {}

/** 由 main.js 注入:会话失效时该做什么(通常是跳登录页)。 */
export function onSessionExpired(handler) {
  sessionExpiredHandler = handler
}

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function currentUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

export function saveSession(payload) {
  localStorage.setItem(TOKEN_KEY, payload.token)
  localStorage.setItem(USER_KEY, JSON.stringify({ username: payload.username, role: payload.role }))
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

/**
 * 用 httpOnly cookie 里的刷新令牌换一个新的访问令牌。
 *
 * <p>"同时在飞只允许一个":页面加载时会并发好几个请求,如果每个 401 都去刷新,
 * 就会拿同一枚刷新令牌并发提交 —— 服务端会把它判成重放并吊销整族,用户直接被踢下线。
 * 所以用一个共享的 Promise 把并发收敛成一次。
 */
let refreshing = null

export async function tryRefresh() {
  if (!refreshing) {
    refreshing = fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' })
            .then((response) => (response.ok ? response.json() : null))
            .then((payload) => {
              if (payload && payload.code === 0) {
                saveSession(payload.data)
                return payload.data
              }
              return null
            })
            .catch(() => null)
            .finally(() => {
              refreshing = null
            })
  }
  return refreshing
}

async function request(path, { method = 'GET', body, form } = {}) {
  const headers = {}
  if (!form) {
    headers['Content-Type'] = 'application/json'
  }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  const send = () => fetch(path, {
    method,
    headers,
    body: form ? body : body === undefined ? undefined : JSON.stringify(body),
  })

  let response
  try {
    response = await send()
  } catch (e) {
    // 网络层失败时 fetch 抛的是 TypeError,文案是 "Failed to fetch",对用户没有信息量
    throw new ApiError('无法连接服务,请确认后端已启动', 0)
  }

  const isAuthPath = path.startsWith('/api/auth')
  if (response.status === 401 && !isAuthPath) {
    // 访问令牌 30 分钟就过期,先用手上的刷新令牌静默换一次,换到了就把原请求重放一遍 ——
    // 用户不该因为"正好在第 31 分钟点了一下"就看见登录页
    const renewed = await tryRefresh()
    if (renewed) {
      headers.Authorization = `Bearer ${renewed.token}`
      response = await send()
    }
    if (response.status === 401) {
      clearSession()
      sessionExpiredHandler()
      throw new ApiError('登录已失效,请重新登录', 401)
    }
  }

  const text = await response.text()
  let payload = null
  try {
    payload = text ? JSON.parse(text) : null
  } catch {
    // 非 JSON(例如反代返回的 HTML 错误页),保留原始文本给排查用
    throw new ApiError(text ? text.slice(0, 200) : `请求失败(${response.status})`, response.status)
  }

  if (!response.ok || (payload && payload.code !== 0)) {
    throw new ApiError((payload && payload.message) || `请求失败(${response.status})`, response.status)
  }
  return payload ? payload.data : null
}

/**
 * 流式提问。
 *
 * <h2>为什么不用 EventSource</h2>
 * EventSource 不能带自定义头,而我们的鉴权是 {@code Authorization: Bearer}。
 * 把令牌塞进 URL 会进浏览器历史、Referer 和服务端访问日志 —— 那是拿凭据换便利。
 * 所以用 {@code fetch} + {@code ReadableStream} 自己解析 SSE 帧,头部照常带。
 *
 * <h2>三件必须做对的事</h2>
 * 1. <b>帧要按 {@code \n\n} 切</b>:一个事件可能跨多个网络包到达,也可能一个包里有两个事件。
 *    把不完整的尾巴留在 buffer 里,是 SSE 解析的全部要点;少做这一步表现为"偶尔丢几个字"。
 * 2. <b>要消费 {@code open} 事件里的 streamId</b>:它是断线重连的唯一钥匙。
 * 3. <b>断线要能续传</b>:带着 {@code lastEventId} 回来取,而不是让用户再等一遍三秒;
 *    访问令牌过期(401)时先静默刷新再重发,不能直接把人踢去登录页。
 *
 * @param {string} question
 * @param {{onDelta?:Function, onFinal?:Function}} handlers
 */
export async function askStream(question, { onDelta, onFinal } = {}) {
  const state = { streamId: null, lastEventId: 0, final: null }

  const dispatch = (eventName, rawData, eventId) => {
    if (eventId) {
      const parsed = Number(eventId)
      if (!Number.isNaN(parsed)) state.lastEventId = parsed
    }
    let payload
    try {
      payload = JSON.parse(rawData)
    } catch {
      return
    }
    if (eventName === 'open') {
      state.streamId = payload.streamId || null
    } else if (eventName === 'delta') {
      onDelta?.(payload.text || '')
    } else if (eventName === 'final') {
      state.final = payload
      onFinal?.(payload)
    } else if (eventName === 'error') {
      const error = new ApiError(payload.message || '生成失败', 503)
      error.retryable = Boolean(payload.retryable)
      throw error
    }
  }

  const consume = async (response) => {
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    try {
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let boundary = buffer.indexOf('\n\n')
        while (boundary >= 0) {
          const frame = buffer.slice(0, boundary)
          buffer = buffer.slice(boundary + 2)
          parseFrame(frame, dispatch)
          boundary = buffer.indexOf('\n\n')
        }
      }
      if (buffer.trim()) parseFrame(buffer, dispatch)
    } finally {
      reader.cancel().catch(() => {})
    }
  }

  const send = () => {
    const token = getToken()
    return fetch('/api/qa/stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ question }),
    })
  }

  const run = async () => {
    let response = await send()

    // 访问令牌 30 分钟就过期,而流式请求走的是另一条代码路径 ——
    // 这里不刷新的话,挂着页面半小时后提问会被直接踢去登录页
    if (response.status === 401) {
      if (!(await tryRefresh())) {
        clearSession()
        sessionExpiredHandler()
        throw new ApiError('登录已失效,请重新登录', 401)
      }
      response = await send()
    }
    if (!response.ok || !response.body) {
      throw new ApiError(`流式请求失败(${response.status})`, response.status)
    }
    await consume(response)
  }

  try {
    await run()
  } catch (e) {
    // 网络中断(不是服务端明确报错)才值得续传:服务端说"生成失败"时,补历史也没用
    if (e instanceof ApiError && e.status === 503) {
      throw e
    }
    if (!state.streamId || state.final) {
      throw e
    }
    await resume(state, dispatch)
  }

  if (!state.final) {
    throw new ApiError('连接中断,未收到定稿', 0)
  }
  return state.final
}

/** 解析一个 SSE 帧:取 event / data / id 三个字段,其余忽略。 */
function parseFrame(frame, dispatch) {
  let eventName = 'message'
  let eventId = null
  const dataLines = []
  for (const line of frame.split('\n')) {
    if (line.startsWith('event:')) eventName = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    else if (line.startsWith('id:')) eventId = line.slice(3).trim()
  }
  if (dataLines.length) {
    dispatch(eventName, dataLines.join('\n'), eventId)
  }
}

/** 带 lastEventId 回去补齐错过的事件;原流还在跑时服务端会发 replay-end 并关连接。 */
async function resume(state, dispatch) {
  const token = getToken()
  const query = `?lastEventId=${state.lastEventId}`
  const response = await fetch(`/api/qa/stream/${state.streamId}${query}`, {
    headers: {
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  })
  if (!response.ok || !response.body) {
    throw new ApiError(`续传失败(${response.status})`, response.status)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        parseFrame(buffer.slice(0, boundary), dispatch)
        buffer = buffer.slice(boundary + 2)
        boundary = buffer.indexOf('\n\n')
      }
    }
  } finally {
    reader.cancel().catch(() => {})
  }
}

export const api = {
  register: (username, password) => request('/api/auth/register', {
    method: 'POST',
    body: { username, password },
  }),
  login: (username, password) => request('/api/auth/login', {
    method: 'POST',
    body: { username, password },
  }),
  listDocuments: () => request('/api/documents'),
  upload: (file) => {
    const form = new FormData()
    form.append('file', file)
    return request('/api/documents', { method: 'POST', body: form, form: true })
  },
  ingest: (id) => request(`/api/documents/${id}/ingest`, { method: 'POST' }),
  vectorize: (id) => request(`/api/documents/${id}/vectorize`, { method: 'POST' }),
  ask: (question) => request('/api/qa', { method: 'POST', body: { question } }),
  logout: () => fetch('/api/auth/logout', { method: 'POST', credentials: 'same-origin' })
          .catch(() => null),
  askStream,
}
