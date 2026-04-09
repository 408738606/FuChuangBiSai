<script setup>
import { computed, onMounted, ref } from 'vue'

const API_BASE = 'http://localhost:8080/api'
const tabs = ['对话任务', '知识库', '模型配置', '输出中心']
const activeTab = ref(tabs[0])

const message = ref('')
const outputFormat = ref('txt')
const createOutput = ref(true)
const saveOutputToKb = ref(false)
const templateDocumentId = ref('')
const sourceDocumentIds = ref([])

const chatAnswer = ref('')
const chatCitations = ref([])
const chatOutputs = ref([])
const chatTrace = ref([])
const mappedFields = ref({})

const docs = ref([])
const selectedUpload = ref(null)
const docPreview = ref('')

const modelConfig = ref({ mode: 'LOCAL', apiBaseUrl: '', apiKey: '', modelName: 'local-rag-agent' })

const outputs = ref([])
const outputPreview = ref('')

const loading = ref(false)
const errorMessage = ref('')
const successMessage = ref('')

const quickPrompts = [
  '请根据已上传文档提取“2024年杭州空气质量优良天数”并生成xlsx',
  '从知识库中抽取“城市+GDP+同比”字段并按表头自动填充模板',
  '筛选2020/7/1~2020/8/31期间上海市相关指标并输出docx',
]

const totalMappedFields = computed(() => Object.keys(mappedFields.value || {}).length)

const fetchJson = async (url, options = {}) => {
  const res = await fetch(url, options)
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || 'request failed')
  }
  return res.json()
}

const withFeedback = async (action, successText) => {
  errorMessage.value = ''
  successMessage.value = ''
  try {
    await action()
    successMessage.value = successText
  } catch (e) {
    errorMessage.value = e.message || '请求失败'
  }
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

const uploadFile = async () => {
  if (!selectedUpload.value) return
  await withFeedback(async () => {
    const form = new FormData()
    form.append('file', selectedUpload.value)
    await fetchJson(`${API_BASE}/kb/upload`, { method: 'POST', body: form })
    selectedUpload.value = null
    await loadDocs()
  }, '文件上传并入库成功')
}

const onUploadSelected = (e) => {
  selectedUpload.value = e.target.files?.[0] || null
}

const previewDocument = async (id) => {
  errorMessage.value = ''
  const res = await fetch(`${API_BASE}/kb/documents/${id}/preview`)
  docPreview.value = await res.text()
}

const sendChat = async () => {
  loading.value = true
  errorMessage.value = ''
  successMessage.value = ''
  try {
    const payload = {
      message: message.value,
      outputFormat: outputFormat.value,
      createOutput: createOutput.value,
      saveOutputToKb: saveOutputToKb.value,
      templateDocumentId: templateDocumentId.value || null,
      sourceDocumentIds: sourceDocumentIds.value,
    }
    const data = await fetchJson(`${API_BASE}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    })
    chatAnswer.value = data.answer || ''
    chatCitations.value = data.citations || []
    chatOutputs.value = data.outputs || []
    chatTrace.value = data.trace || []
    mappedFields.value = data.mappedFields || {}
    await loadOutputs()
    successMessage.value = '任务执行成功'
  } catch (e) {
    errorMessage.value = e.message || '任务执行失败'
  } finally {
    loading.value = false
  }
}

const usePrompt = (prompt) => {
  message.value = prompt
}

const updateModelConfig = async () => {
  await withFeedback(async () => {
    await fetchJson(`${API_BASE}/models/config`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(modelConfig.value),
    })
  }, '模型配置已保存')
}

const previewOutput = async (id) => {
  errorMessage.value = ''
  const res = await fetch(`${API_BASE}/outputs/${id}/preview`)
  outputPreview.value = await res.text()
}

const saveOutputToKnowledge = async (id) => {
  await withFeedback(async () => {
    await fetchJson(`${API_BASE}/outputs/${id}/save-to-kb`, { method: 'POST' })
    await loadDocs()
  }, '产物已保存到知识库')
}

const downloadUrl = (type, id) => {
  if (type === 'doc') return `${API_BASE}/kb/documents/${id}/download`
  return `${API_BASE}/outputs/${id}/download`
}

onMounted(async () => {
  await Promise.all([loadDocs(), loadOutputs(), loadModelConfig()])
})
</script>

<template>
  <div class="container">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-title">FuChuang A23</div>
        <div class="brand-sub">智能任务中台</div>
      </div>
      <button
        v-for="tab in tabs"
        :key="tab"
        :class="{ active: activeTab === tab }"
        @click="activeTab = tab"
      >
        {{ tab }}
      </button>
    </aside>

    <main class="content">
      <div class="banner success" v-if="successMessage">{{ successMessage }}</div>
      <div class="banner error" v-if="errorMessage">{{ errorMessage }}</div>

      <section v-if="activeTab === '对话任务'">
        <div class="card hero">
          <div>
            <h3>模型对话框（统一任务入口）</h3>
            <p class="small">问答 / 抽取 / 填表 / 生成在同一入口执行，支持来源文档与模板联动。</p>
          </div>
          <div class="chips">
            <button class="chip" v-for="prompt in quickPrompts" :key="prompt" @click="usePrompt(prompt)">{{ prompt }}</button>
          </div>
        </div>

        <div class="card">
          <textarea v-model="message" rows="4" placeholder="输入任务，如：根据模板提取城市GDP并自动填表" />
          <div class="row mt8">
            <select v-model="outputFormat" class="w-auto">
              <option value="txt">txt</option>
              <option value="md">md</option>
              <option value="docx">docx</option>
              <option value="xlsx">xlsx</option>
            </select>
            <label><input type="checkbox" v-model="createOutput" /> 生成输出文件</label>
            <label><input type="checkbox" v-model="saveOutputToKb" /> 输出后保存到知识库</label>
          </div>

          <div class="row mt8">
            <select v-model="templateDocumentId" class="w-40">
              <option value="">（可选）模板文件</option>
              <option v-for="d in docs" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select>
            <select v-model="sourceDocumentIds" multiple class="w-60 multi-select">
              <option v-for="d in docs" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select>
          </div>

          <button class="primary mt8" :disabled="loading" @click="sendChat">
            {{ loading ? '处理中...' : '执行任务' }}
          </button>
        </div>

        <div class="grid two">
          <div class="card" v-if="chatAnswer">
            <h4>回答</h4>
            <pre>{{ chatAnswer }}</pre>
          </div>

          <div class="card" v-if="chatTrace.length">
            <h4>任务追踪</h4>
            <div class="trace-item" v-for="(step, idx) in chatTrace" :key="step + idx">
              <span class="dot"></span>
              <span>{{ step }}</span>
            </div>
          </div>
        </div>

        <div class="card" v-if="totalMappedFields">
          <h4>字段自动映射（{{ totalMappedFields }}）</h4>
          <div class="mapping-grid">
            <div class="mapping-item" v-for="(value, key) in mappedFields" :key="key">
              <div class="small">{{ key }}</div>
              <div>{{ value }}</div>
            </div>
          </div>
        </div>

        <div class="card" v-if="chatCitations.length">
          <h4>引用片段</h4>
          <div class="list-item" v-for="c in chatCitations" :key="c.documentId + c.snippet">
            <div><b>{{ c.documentName }}</b></div>
            <div class="small">{{ c.snippet }}</div>
          </div>
        </div>

        <div class="card" v-if="chatOutputs.length">
          <h4>任务产物</h4>
          <div class="list-item" v-for="o in chatOutputs" :key="o.id">
            <div><b>{{ o.name }}</b> <span class="small">{{ o.createdAt }}</span></div>
            <div class="row mt8">
              <button class="secondary" @click="previewOutput(o.id)">预览</button>
              <a class="secondary" :href="downloadUrl('out', o.id)" target="_blank">下载</a>
              <button class="secondary" @click="saveOutputToKnowledge(o.id)">保存到知识库</button>
            </div>
          </div>
        </div>
      </section>

      <section v-else-if="activeTab === '知识库'">
        <div class="card">
          <h3>知识库上传与管理</h3>
          <div class="row">
            <input type="file" @change="onUploadSelected" />
            <button class="primary" @click="uploadFile">上传并入库</button>
          </div>
        </div>
        <div class="card">
          <h4>文档列表（{{ docs.length }}）</h4>
          <div class="list-item" v-for="d in docs" :key="d.id">
            <div><b>{{ d.name }}</b> <span class="small">{{ d.extension }} / chunks={{ d.chunkCount }}</span></div>
            <div class="row mt8">
              <button class="secondary" @click="previewDocument(d.id)">预览</button>
              <a class="secondary" :href="downloadUrl('doc', d.id)" target="_blank">下载</a>
            </div>
          </div>
        </div>
        <div class="card" v-if="docPreview">
          <h4>文档预览</h4>
          <pre>{{ docPreview }}</pre>
        </div>
      </section>

      <section v-else-if="activeTab === '模型配置'">
        <div class="card">
          <h3>模型配置中心</h3>
          <div class="row">
            <select v-model="modelConfig.mode" class="w-auto">
              <option value="LOCAL">LOCAL</option>
              <option value="API">API</option>
            </select>
            <input v-model="modelConfig.modelName" placeholder="模型名" />
          </div>
          <div class="mt8">
            <input v-model="modelConfig.apiBaseUrl" placeholder="API Base URL（OpenAI兼容）" />
          </div>
          <div class="mt8">
            <input v-model="modelConfig.apiKey" placeholder="API Key" />
          </div>
          <button class="primary mt8" @click="updateModelConfig">保存配置</button>
        </div>
      </section>

      <section v-else>
        <div class="card">
          <h3>输出中心（{{ outputs.length }}）</h3>
          <div class="list-item" v-for="o in outputs" :key="o.id">
            <div><b>{{ o.name }}</b> <span class="small">{{ o.createdAt }}</span></div>
            <div class="row mt8">
              <button class="secondary" @click="previewOutput(o.id)">预览</button>
              <a class="secondary" :href="downloadUrl('out', o.id)" target="_blank">下载</a>
              <button class="secondary" @click="saveOutputToKnowledge(o.id)">保存到知识库</button>
            </div>
          </div>
        </div>
        <div class="card" v-if="outputPreview">
          <h4>输出预览</h4>
          <pre>{{ outputPreview }}</pre>
        </div>
      </section>
    </main>
  </div>
</template>
