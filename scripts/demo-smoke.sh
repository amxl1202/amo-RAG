#!/usr/bin/env bash
# 端到端冒烟:注册/登录 → 列文档 → 问一个文档内问题 → 问一个文档外问题。
#
# 为什么用脚本而不是只写"打开浏览器点一下":
# 这三步覆盖了认证、检索、生成、引用、拒答五条路径。用脚本跑,任何一次改动之后
# 都能用一条命令确认"整条链路还是通的",而不是等到演示现场才发现登录接口 500。
#
# 前置:后端已在 8080 运行,数据库已在跑。
# 用法:
#   scripts/demo-smoke.sh                      # 用 demo / demo12345
#   BASE=http://localhost:8080 scripts/demo-smoke.sh
set -euo pipefail

# 本机地址不走环境代理:开发机上常挂着代理,curl 会把 localhost 也发过去,
# 得到 502 而不是真实响应 —— 那种失败看起来像"后端挂了",排查方向会被带偏
export NO_PROXY="localhost,127.0.0.1,${NO_PROXY:-}"

BASE="${BASE:-http://localhost:8080}"
USER_NAME="${USER_NAME:-demo}"
PASSWORD="${PASSWORD:-demo12345}"

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\033[31m失败:%s\033[0m\n' "$*" >&2; exit 1; }

say "1/4 登录（不存在则先注册）"
LOGIN=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" || true)
TOKEN=$(printf '%s' "$LOGIN" | python -c "import sys,json;d=json.load(sys.stdin);print(d.get('data',{}).get('token','') if d.get('code')==0 else '')")
if [ -z "$TOKEN" ]; then
  echo "  登录失败，尝试注册…"
  TOKEN=$(curl -s -X POST "$BASE/api/auth/register" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" \
    | python -c "import sys,json;d=json.load(sys.stdin);print(d.get('data',{}).get('token','') if d.get('code')==0 else '')")
fi
[ -n "$TOKEN" ] || fail "拿不到令牌（检查用户名/密码是否符合规则）"
echo "  令牌长度 ${#TOKEN}"

say "2/4 未带令牌必须被拒（默认拒绝）"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/documents")
[ "$CODE" = "401" ] || fail "未带令牌访问 /api/documents 返回 $CODE，应为 401"
echo "  401 ✓"

say "3/4 带令牌列文档"
DOCS=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/documents")
echo "$DOCS" | python -c "
import sys, json
d = json.load(sys.stdin)
rows = d.get('data') or []
print('  文档数 %d' % len(rows))
for r in rows:
    print('   - %-28s %s' % (r['originalName'], r['status']))
if not rows:
    raise SystemExit('  库里没有文档，先上传并向量化再跑这个脚本')
"

ask() {
  local question="$1"
  curl -s -X POST "$BASE/api/qa" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "{\"question\":\"$question\"}"
}

say "4/4 问答"
Q1='HNSW 与 IVFFlat 的核心差别在哪里？'
echo "  问：$Q1"
ask "$Q1" | python -c "
import sys, json
d = json.load(sys.stdin)
a = d['data']
print('  状态：%s' % a['status'])
print('  答案：%s' % a['answer'].strip())
print('  引用：%s' % ', '.join('%s→%s' % (c['label'], c['documentTitle']) for c in a['citations']))
if a['status'] != 'ANSWERED':
    raise SystemExit(1)
" || fail "文档内问题应当作答"

Q2='这篇笔记里提到的服务器型号是什么？'
echo "  问：$Q2"
ask "$Q2" | python -c "
import sys, json
d = json.load(sys.stdin)
a = d['data']
print('  状态：%s（原因 %s）' % (a['status'], a['reason']))
print('  答案：%s' % a['answer'].strip())
if a['status'] != 'REFUSED':
    raise SystemExit(1)
" || fail "文档外问题应当拒答"

say "全部通过：认证 / 默认拒绝 / 检索 / 引用 / 拒答"
