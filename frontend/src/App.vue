<script setup>
import { computed, onMounted, ref } from 'vue'

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080/api'

const loading = ref(false)
const uploadLoading = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

const message = ref('')
const outputFormat = ref('txt')
const createOutput = ref(true)
const saveOutputToKb = ref(false)
const templateDocumentId = ref('')
const selectedDocIds = ref([])

const docs = ref([])
const outputs = ref([])
const modelConfig = ref({ mode: 'LOCAL', apiBaseUrl: '', apiKey: '', modelName: 'local-rag-agent' })

const chatMessages = ref([])
const selectedUploadFiles = ref([])
const docPreview = ref('')
const outputPreview = ref('')

const quickPrompts = [
  '请基于已上传文档提取关键指标并生成xlsx结果文件',
  '根据模板文件自动映射字段并输出docx，未命中字段请标注',
  '请筛选指定日期区间的数据，完成表格填充并返回下载文件',
]

const selectedDocs = computed(() => docs.value.filter((d) => selectedDocIds.value.includes(d.id)))

const fetchJson = async (url, options = {}) => {
  const res = await fetch(url, options)
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || 'request failed')
  }
  return res.json()
}

const clearFeedback = () => {
  errorMessage.value = ''
  successMessage.value = ''
}

const loadDocs = async () => {
  docs.value = await fetchJson(`${API_BASE}/kb/documents`)
}

const loadOutputs = async () => {
  outputs.value = await fetchJson(`${API_BASE}/outputs`)
}

const loadModelConfig = async () => {
  modelConfig.value = await fetchJson(`${API_BASE}/models/config`)
}

const uploadFilesToKb = async () => {
  if (!selectedUploadFiles.value.length) return
  uploadLoading.value = true
  clearFeedback()
  try {
    const uploaded = []
    for (const file of selectedUploadFiles.value) {
      const form = new FormData()
      form.append('file', file)
      const data = await fetchJson(`${API_BASE}/kb/upload`, { method: 'POST', body: form })
      uploaded.push(data)
    }

    await loadDocs()
    const merged = new Set(selectedDocIds.value)
    uploaded.forEach((d) => merged.add(d.id))
    selectedDocIds.value = [...merged]

    chatMessages.value.push({
      id: crypto.randomUUID(),
      role: 'system',
      text: `已上传 ${uploaded.length} 个文档并自动加入本轮任务数据源。`,
      createdAt: new Date().toISOString(),
    })

    selectedUploadFiles.value = []
    successMessage.value = '文档上传完成'
  } catch (e) {
    errorMessage.value = e.message || '上传失败'
  } finally {
    uploadLoading.value = false
  }
}

const onUploadSelected = (e) => {
  selectedUploadFiles.value = [...(e.target.files || [])]
}

const sendChat = async () => {
  const trimmed = message.value.trim()
  if (!trimmed) return

  clearFeedback()
  loading.value = true

  chatMessages.value.push({
    id: crypto.randomUUID(),
    role: 'user',
    text: trimmed,
    createdAt: new Date().toISOString(),
    selectedDocs: selectedDocs.value.map((d) => d.name),
    outputFormat: outputFormat.value,
  })

  try {
    const payload = {
      message: trimmed,
      outputFormat: outputFormat.value,
      createOutput: createOutput.value,
      saveOutputToKb: saveOutputToKb.value,
      templateDocumentId: templateDocumentId.value || null,
      sourceDocumentIds: selectedDocIds.value,
    }

    const data = await fetchJson(`${API_BASE}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })

    chatMessages.value.push({
      id: crypto.randomUUID(),
      role: 'assistant',
      text: data.answer || '',
      createdAt: new Date().toISOString(),
      citations: data.citations || [],
      outputs: data.outputs || [],
      trace: data.trace || [],
      mappedFields: data.mappedFields || {},
    })

    message.value = ''
    await loadOutputs()
    successMessage.value = '任务执行成功'
  } catch (e) {
    errorMessage.value = e.message || '任务执行失败'
    chatMessages.value.push({
      id: crypto.randomUUID(),
      role: 'assistant',
      text: `执行失败：${errorMessage.value}`,
      createdAt: new Date().toISOString(),
    })
  } finally {
    loading.value = false
  }
}

const usePrompt = (prompt) => {
  message.value = prompt
}

const updateModelConfig = async () => {
  clearFeedback()
  try {
    await fetchJson(`${API_BASE}/models/config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(modelConfig.value),
    })
    successMessage.value = '模型配置已保存'
  } catch (e) {
    errorMessage.value = e.message || '模型配置保存失败'
  }
}

const previewDocument = async (id) => {
  const res = await fetch(`${API_BASE}/kb/documents/${id}/preview`)
  docPreview.value = await res.text()
}

const previewOutput = async (id) => {
  const res = await fetch(`${API_BASE}/outputs/${id}/preview`)
  outputPreview.value = await res.text()
}

const saveOutputToKnowledge = async (id) => {
  clearFeedback()
  try {
    await fetchJson(`${API_BASE}/outputs/${id}/save-to-kb`, { method: 'POST' })
    await loadDocs()
    successMessage.value = '产物已保存到知识库'
  } catch (e) {
    errorMessage.value = e.message || '保存失败'
  }
}

const downloadUrl = (type, id) => {
  if (type === 'doc') return `${API_BASE}/kb/documents/${id}/download`
  return `${API_BASE}/outputs/${id}/download`
}

onMounted(async () => {
  await Promise.all([loadDocs(), loadOutputs(), loadModelConfig()])
  chatMessages.value = [
    {
      id: crypto.randomUUID(),
      role: 'assistant',
      text: '你好，我是任务助手。请上传文档并描述你的任务目标，我会在对话中完成抽取、填表、生成与下载。',
      createdAt: new Date().toISOString(),
    },
  ]
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div>
        <h1>FuChuang 智能任务对话台</h1>
        <p>所有任务在同一对话中完成：上传文档 → 描述任务 → 获取可预览/可下载结果。</p>
      </div>
      <div class="status" v-if="selectedDocs.length">已选数据源：{{ selectedDocs.length }}</div>
    </header>

    <div class="banner success" v-if="successMessage">{{ successMessage }}</div>
    <div class="banner error" v-if="errorMessage">{{ errorMessage }}</div>

    <div class="layout">
      <section class="chat-panel">
        <div class="card quick-prompts">
          <button class="chip" v-for="prompt in quickPrompts" :key="prompt" @click="usePrompt(prompt)">{{ prompt }}</button>
        </div>

        <div class="card messages">
          <article class="msg" :class="msg.role" v-for="msg in chatMessages" :key="msg.id">
            <div class="avatar">{{ msg.role === 'user' ? '我' : msg.role === 'assistant' ? 'AI' : 'SYS' }}</div>
            <div class="bubble">
              <div class="content">{{ msg.text }}</div>

              <div class="meta" v-if="msg.selectedDocs?.length">来源文档：{{ msg.selectedDocs.join('、') }}</div>

              <div class="trace" v-if="msg.trace?.length">
                <div class="trace-title">执行追踪</div>
                <div class="trace-line" v-for="(step, idx) in msg.trace" :key="step + idx">• {{ step }}</div>
              </div>

              <div class="mapping" v-if="msg.mappedFields && Object.keys(msg.mappedFields).length">
                <div class="trace-title">字段映射</div>
                <div class="map-item" v-for="(value, key) in msg.mappedFields" :key="key">
                  <span>{{ key }}</span>
                  <span>{{ value }}</span>
                </div>
              </div>

              <div class="citation" v-if="msg.citations?.length">
                <div class="trace-title">引用片段</div>
                <div class="citation-item" v-for="c in msg.citations" :key="c.documentId + c.snippet">
                  <b>{{ c.documentName }}</b>
                  <div>{{ c.snippet }}</div>
                </div>
              </div>

              <div class="output-list" v-if="msg.outputs?.length">
                <div class="trace-title">任务产物</div>
                <div class="output-item" v-for="o in msg.outputs" :key="o.id">
                  <div>{{ o.name }}</div>
                  <div class="row">
                    <button class="secondary" @click="previewOutput(o.id)">预览</button>
                    <a class="secondary" :href="downloadUrl('out', o.id)" target="_blank">下载</a>
                    <button class="secondary" @click="saveOutputToKnowledge(o.id)">入知识库</button>
                  </div>
                </div>
              </div>
            </div>
          </article>
        </div>

        <div class="card composer">
          <div class="row">
            <textarea v-model="message" rows="3" placeholder="输入任务需求，例如：请从附件中提取字段并自动填充模板，输出xlsx" />
          </div>

          <div class="row">
            <select v-model="outputFormat" class="w-small">
              <option value="txt">txt</option>
              <option value="md">md</option>
              <option value="docx">docx</option>
              <option value="xlsx">xlsx</option>
            </select>
            <select v-model="templateDocumentId" class="w-medium">
              <option value="">（可选）模板文件</option>
              <option v-for="d in docs" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select>
            <label><input type="checkbox" v-model="createOutput" /> 生成文件</label>
            <label><input type="checkbox" v-model="saveOutputToKb" /> 自动入库</label>
          </div>

          <div class="row">
            <input type="file" multiple @change="onUploadSelected" />
            <button class="secondary" :disabled="uploadLoading" @click="uploadFilesToKb">
              {{ uploadLoading ? '上传中...' : '上传到知识库并加入数据源' }}
            </button>
            <button class="primary" :disabled="loading" @click="sendChat">
              {{ loading ? '执行中...' : '发送任务' }}
            </button>
          </div>
        </div>
      </section>

      <aside class="workspace-panel">
        <div class="card">
          <h3>知识库文档</h3>
          <select v-model="selectedDocIds" multiple class="multi-select">
            <option v-for="d in docs" :key="d.id" :value="d.id">{{ d.name }}</option>
          </select>
          <div class="doc-list">
            <div class="doc-item" v-for="d in docs" :key="d.id">
              <div>{{ d.name }}</div>
              <div class="row">
                <button class="secondary" @click="previewDocument(d.id)">预览</button>
                <a class="secondary" :href="downloadUrl('doc', d.id)" target="_blank">下载</a>
              </div>
            </div>
          </div>
        </div>

        <div class="card">
          <h3>模型配置</h3>
          <div class="row">
            <select v-model="modelConfig.mode" class="w-small">
              <option value="LOCAL">LOCAL</option>
              <option value="API">API</option>
            </select>
            <input v-model="modelConfig.modelName" placeholder="模型名" />
          </div>
          <input v-model="modelConfig.apiBaseUrl" placeholder="API Base URL" />
          <input v-model="modelConfig.apiKey" placeholder="API Key" />
          <button class="primary" @click="updateModelConfig">保存模型配置</button>
        </div>

        <div class="card">
          <h3>输出历史</h3>
          <div class="doc-item" v-for="o in outputs" :key="o.id">
            <div>{{ o.name }}</div>
            <div class="row">
              <button class="secondary" @click="previewOutput(o.id)">预览</button>
              <a class="secondary" :href="downloadUrl('out', o.id)" target="_blank">下载</a>
            </div>
          </div>
        </div>
      </aside>
    </div>

    <div class="preview-grid" v-if="docPreview || outputPreview">
      <div class="card" v-if="docPreview">
        <h3>文档预览</h3>
        <pre>{{ docPreview }}</pre>
      </div>
      <div class="card" v-if="outputPreview">
        <h3>产物预览</h3>
        <pre>{{ outputPreview }}</pre>
      </div>
    </div>
  </div>
</template>
