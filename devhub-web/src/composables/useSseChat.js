/**
 * SSE 流式对话消费（协议：__META__ 元事件 / 文本分片 / [DONE]）。
 * 抽自 Chat.vue 的消费循环，供挂件与后续页面复用
 */
export function useSseChat() {
  let ctrl = null

  /**
   * 流式对话；onMeta(meta)、onDelta(累计文本) 回调；返回 Promise<完整文本>。
   * headers 可选（挂件匿名无需 token，内部接口传 Authorization）
   */
  async function stream(url, body, { onMeta, onDelta, headers } = {}) {
    ctrl = new AbortController()
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...(headers || {}) },
      body: JSON.stringify(body),
      signal: ctrl.signal
    })
    const reader = res.body.getReader(), dec = new TextDecoder()
    let buf = '', raw = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += dec.decode(value, { stream: true })
      const events = buf.split('\n\n'); buf = events.pop()
      for (const evt of events) {
        const line = evt.split('\n').find(l => l.startsWith('data:'))
        if (!line) continue
        const data = line.slice(5)
        if (data === '[DONE]') continue
        if (data.startsWith('__META__')) { onMeta && onMeta(JSON.parse(data.slice(8))) }
        else { raw += data; onDelta && onDelta(raw) }
      }
    }
    return raw
  }

  function abort() { ctrl && ctrl.abort() }

  return { stream, abort }
}
