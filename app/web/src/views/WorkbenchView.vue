<script setup lang="ts">
import type { ConversationSummary, TurnSummary } from '@/api/workbench'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import {
  createConversation,
  eventsUrl,
  getConversation,
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

async function loadList() {
  try {
    conversations.value = await listConversations()
    if (activeId.value === null && conversations.value.length > 0) {
      await open(conversations.value[0].id)
    }
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : '加载会话失败'
  }
}

async function open(id: number) {
  closeStream()
  activeId.value = id
  bubbles.value = []
  try {
    const detail = await getConversation(id)
    for (const turn of detail.turns as TurnSummary[]) {
      bubbles.value.push({ role: 'user', text: turn.input })
      if (turn.status === 'DONE') {
        // 历史轮次的回复正文不在库里（事件流才是来源），#5 会补归档
        bubbles.value.push({ role: 'assistant', text: '（历史回复：事件归档见 #5）' })
      }
    }
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : '打开会话失败'
  }
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

/** 打开事件流：cursor 之后的事件逐条喂给当前气泡。 */
function listen(cursor: string) {
  if (activeId.value === null) {
    return
  }
  closeStream()
  const assistant: Bubble = { role: 'assistant', text: '' }
  bubbles.value.push(assistant)

  source = new EventSource(eventsUrl(activeId.value, cursor))

  const onToken = (event: MessageEvent) => {
    const payload = JSON.parse(event.data) as { delta?: string }
    assistant.text += payload.delta ?? ''
  }
  const onDone = () => {
    busy.value = false
    closeStream()
  }
  const onError = (event: MessageEvent) => {
    try {
      const payload = JSON.parse(event.data) as { message?: string }
      error.value = payload.message ?? '生成失败'
    }
    catch {
      error.value = '生成失败'
    }
    busy.value = false
    closeStream()
  }

  source.addEventListener('token', onToken as EventListener)
  source.addEventListener('done', onDone as EventListener)
  source.addEventListener('error', onError as EventListener)
  source.onerror = () => {
    // 服务端正常关闭也会触发 onerror；只有仍在忙碌时才当作失败
    if (busy.value) {
      busy.value = false
      error.value = '事件流中断，请刷新重试'
      closeStream()
    }
  }
}

async function send() {
  const text = input.value.trim()
  if (!text || activeId.value === null || busy.value) {
    return
  }
  busy.value = true
  error.value = ''
  bubbles.value.push({ role: 'user', text })
  input.value = ''

  try {
    const submitted = await submitTurn(activeId.value, text)
    listen(submitted.cursor)
  }
  catch (e) {
    busy.value = false
    error.value = e instanceof Error ? e.message : '提交失败'
  }
}

onMounted(loadList)
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
