<script setup lang="ts">
import type { ConversationSummary } from '@/api/workbench'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import {
  createConversation,
  eventsUrl,
  listConversations,
  submitTurn,
} from '@/api/workbench'

interface Bubble {
  role: 'user' | 'assistant'
  text: string
}

const conversations = ref<ConversationSummary[]>([])
const activeId = ref<number | null>(null)
const bubbles = ref<Bubble[]>([])
const input = ref('')
const busy = ref(false)
const error = ref('')

let source: EventSource | null = null
/** 当前正在接收 token 的助手气泡下标。 */
let streamingIndex: number | null = null
/** 打开会话的请求序号：并发打开时只让最后一次生效。 */
let openSeq = 0

async function loadList() {
  try {
    conversations.value = await listConversations()
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : '加载会话失败'
  }
}

async function open(id: number) {
  const seq = ++openSeq
  closeStream()
  activeId.value = id
  bubbles.value = []
  streamingIndex = null
  error.value = ''
  await loadList()
  if (seq !== openSeq) {
    return
  }
  // 不带 cursor：服务端从第一条事件重放整段历史，然后继续推增量。
  // 这样"刷新页面看到完整历史"与"实时看到新事件"是同一套代码路径。
  listen(id)
}

async function create() {
  try {
    const created = await createConversation('新会话')
    await loadList()
    await open(created.id)
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : '创建会话失败'
  }
}

function closeStream() {
  source?.close()
  source = null
}

function listen(conversationId: number) {
  closeStream()
  source = new EventSource(eventsUrl(conversationId))

  source.addEventListener('turn/queued', (event) => {
    const payload = JSON.parse((event as MessageEvent).data) as { input?: string }
    // 用户气泡也由事件驱动：重放时才能还原历史提问
    bubbles.value.push({ role: 'user', text: payload.input ?? '' })
    bubbles.value.push({ role: 'assistant', text: '' })
    streamingIndex = bubbles.value.length - 1
    busy.value = true
  })

  source.addEventListener('token', (event) => {
    const payload = JSON.parse((event as MessageEvent).data) as { delta?: string }
    const bubble = streamingIndex === null ? null : bubbles.value[streamingIndex]
    if (bubble) {
      bubble.text += payload.delta ?? ''
    }
  })

  source.addEventListener('done', () => {
    streamingIndex = null
    busy.value = false
  })

  source.addEventListener('error', (event) => {
    const messageEvent = event as MessageEvent
    if (!messageEvent.data) {
      return
    }
    try {
      const payload = JSON.parse(messageEvent.data) as { message?: string }
      error.value = payload.message ?? '生成失败'
    }
    catch {
      error.value = '生成失败'
    }
    streamingIndex = null
    busy.value = false
  })

  source.onerror = () => {
    // 服务端正常收尾也会触发；只有仍在生成时才当作异常
    if (busy.value) {
      busy.value = false
      streamingIndex = null
      error.value = '事件流中断，刷新可恢复'
      closeStream()
    }
  }
}

async function send() {
  const text = input.value.trim()
  const conversationId = activeId.value
  if (!text || conversationId === null || busy.value) {
    return
  }
  busy.value = true
  error.value = ''
  input.value = ''

  try {
    await submitTurn(conversationId, text)
    // 气泡由 SSE 的 turn/queued 事件渲染，这里不本地插入，避免与重放重复
  }
  catch (e) {
    busy.value = false
    error.value = e instanceof Error ? e.message : '提交失败'
  }
}

onMounted(async () => {
  await loadList()
  if (conversations.value.length > 0) {
    await open(conversations.value[0].id)
  }
})
onBeforeUnmount(closeStream)
</script>

<template>
  <div class="workbench">
    <aside class="workbench__side">
      <el-button class="mb-3 w-full" type="primary" data-testid="new-conversation" @click="create">
        新建会话
      </el-button>
      <ul class="workbench__list" data-testid="conversation-list">
        <li
          v-for="item in conversations"
          :key="item.id"
          :class="{ active: item.id === activeId }"
          :data-testid="`conversation-${item.id}`"
          @click="open(item.id)"
        >
          {{ item.title }}
        </li>
      </ul>
    </aside>

    <section class="workbench__main">
      <el-alert v-if="error" :title="error" type="error" :closable="false" class="mb-3" />

      <div class="workbench__bubbles" data-testid="bubbles">
        <p v-if="bubbles.length === 0" class="text-muted">
          还没有对话，输入一句话开始。
        </p>
        <div
          v-for="(bubble, index) in bubbles"
          :key="index"
          class="bubble"
          :class="`bubble--${bubble.role}`"
          :data-testid="`bubble-${bubble.role}`"
        >
          {{ bubble.text }}
        </div>
      </div>

      <div class="workbench__composer">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          placeholder="输入内容，Enter 发送（Shift+Enter 换行）"
          data-testid="composer"
          @keydown.enter.exact.prevent="send"
        />
        <el-button
          type="primary"
          :loading="busy"
          :disabled="activeId === null"
          data-testid="send"
          @click="send"
        >
          发送
        </el-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.workbench {
  display: grid;
  gap: 16px;
  grid-template-columns: 220px 1fr;
  height: calc(100vh - 120px);
}
.workbench__side {
  border-right: 1px solid var(--el-border-color);
  padding-right: 12px;
}
.workbench__list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.workbench__list li {
  cursor: pointer;
  padding: 8px;
  border-radius: 6px;
}
.workbench__list li.active {
  background: var(--el-fill-color-light);
  font-weight: 600;
}
.workbench__main {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.workbench__bubbles {
  flex: 1;
  overflow-y: auto;
  padding: 4px;
}
.bubble {
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  white-space: pre-wrap;
}
.bubble--user {
  background: var(--el-color-primary-light-9);
}
.bubble--assistant {
  background: var(--el-fill-color-light);
}
.workbench__composer {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  padding-top: 8px;
}
</style>
