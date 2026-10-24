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

async function request(path, { method = 'GET', body, form } = {}) {
  const headers = {}
  if (!form) {
    headers['Content-Type'] = 'application/json'
  }
  const token = getToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  let response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: form ? body : body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (e) {
    // 网络层失败时 fetch 抛的是 TypeError,文案是 "Failed to fetch",对用户没有信息量
    throw new ApiError('无法连接服务,请确认后端已启动', 0)
  }

  const isAuthPath = path.startsWith('/api/auth')
  if (response.status === 401 && !isAuthPath) {
    clearSession()
    sessionExpiredHandler()
    throw new ApiError('登录已失效,请重新登录', 401)
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
 * <h2>为什么要自己拼帧</h2>
 * 一个事件可能跨多个 TCP 包到达,也可能一个包里有两个事件。按 {@code \n\n} 切分、
 * 把不完整的尾巴留在 buffer 里,是 SSE 解析的全部要点;少做这一步,
 * 表现是"偶尔丢几个字",而不会报错。
 *
 * @param {string} question
 * @param {{onDelta?:Function, onFinal?:Function}} handlers
 */
export async function askStream(question, { onDelta, onFinal } = {}) {
  const token = getToken()
  const response = await fetch('/api/qa/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ question }),
  })

  if (response.status === 401) {
    clearSession()
    sessionExpiredHandler()
    throw new ApiError('登录已失效,请重新登录', 401)
  }
  if (!response.ok || !response.body) {
    throw new ApiError(`流式请求失败(${response.status})`, response.status)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let final = null

  const dispatch = (frame) => {
    const lines = frame.split('\n')
    let event = 'message'
    const dataLines = []
    for (const line of lines) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    }
    if (!dataLines.length) return
    let payload
    try {
      payload = JSON.parse(dataLines.join('\n'))
    } catch {
      return
    }
    if (event === 'delta') onDelta?.(payload.text || '')
    else if (event === 'final') {
      final = payload
      onFinal?.(payload)
    } else if (event === 'error') {
      throw new ApiError(payload.message || '生成失败', 503)
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const frame = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        if (frame.trim()) dispatch(frame)
        boundary = buffer.indexOf('\n\n')
      }
    }
    if (buffer.trim()) dispatch(buffer)
  } finally {
    reader.cancel().catch(() => {})
  }
  return final
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
  askStream,
}
