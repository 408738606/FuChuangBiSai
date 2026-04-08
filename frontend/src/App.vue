<script setup>
import { onMounted, ref } from 'vue'

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

const docs = ref([])
const selectedUpload = ref(null)
const docPreview = ref('')

const modelConfig = ref({ mode: 'LOCAL', apiBaseUrl: '', apiKey: '', modelName: 'local-rag-agent' })

const outputs = ref([])
const outputPreview = ref('')

const loading = ref(false)

const fetchJson = async (url, options = {}) => {
  const res = await fetch(url, options)
  if (!res.ok) {
    const text = await res.text()
    throw new Error(text || 'request failed')
  }
  return res.json()
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
  const form = new FormData()
  form.append('file', selectedUpload.value)
  await fetchJson(`${API_BASE}/kb/upload`, { method: 'POST', body: form })
  selectedUpload.value = null
  await loadDocs()
}

const onUploadSelected = (e) => {
  selectedUpload.value = e.target.files?.[0] || null
}

const previewDocument = async (id) => {
  const res = await fetch(`${API_BASE}/kb/documents/${id}/preview`)
  docPreview.value = await res.text()
}

const sendChat = async () => {
  loading.value = true
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
    chatAnswer.value = data.answer
    chatCitations.value = data.citations || []
    chatOutputs.value = data.outputs || []
    await loadOutputs()
  } finally {
    loading.value = false
  }
}

const updateModelConfig = async () => {
  await fetchJson(`${API_BASE}/models/config`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(modelConfig.value),
  })
}

const previewOutput = async (id) => {
  const res = await fetch(`${API_BASE}/outputs/${id}/preview`)
  outputPreview.value = await res.text()
}

const saveOutputToKnowledge = async (id) => {
  await fetchJson(`${API_BASE}/outputs/${id}/save-to-kb`, { method: 'POST' })
  await loadDocs()
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
      <h3>FuChuang A23</h3>
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
      <section v-if="activeTab === '对话任务'">
        <div class="card">
          <h3>模型对话框（统一任务入口）</h3>
          <textarea v-model="message" rows="4" placeholder="输入问答/抽取/填表任务" />
          <div class="row" style="margin-top: 8px">
            <select v-model="outputFormat" style="max-width: 150px">
              <option value="txt">txt</option>
              <option value="md">md</option>
              <option value="docx">docx</option>
              <option value="xlsx">xlsx</option>
            </select>
            <label><input type="checkbox" v-model="createOutput" /> 生成输出文件</label>
            <label><input type="checkbox" v-model="saveOutputToKb" /> 输出后保存到知识库</label>
          </div>

          <div class="row" style="margin-top: 8px">
            <select v-model="templateDocumentId" style="max-width: 260px">
              <option value="">（可选）模板文件</option>
              <option v-for="d in docs" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select>
            <select v-model="sourceDocumentIds" multiple style="min-height: 88px">
              <option v-for="d in docs" :key="d.id" :value="d.id">{{ d.name }}</option>
            </select>
          </div>

          <button class="primary" :disabled="loading" @click="sendChat" style="margin-top: 8px">
            {{ loading ? '处理中...' : '执行任务' }}
          </button>
        </div>

        <div class="card" v-if="chatAnswer">
          <h4>回答</h4>
          <pre>{{ chatAnswer }}</pre>
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
            <div class="row" style="margin-top: 6px">
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
          <input type="file" @change="onUploadSelected" />
          <button class="primary" style="margin-top: 8px" @click="uploadFile">上传并入库</button>
        </div>
        <div class="card">
          <h4>文档列表</h4>
          <div class="list-item" v-for="d in docs" :key="d.id">
            <div><b>{{ d.name }}</b> <span class="small">{{ d.extension }} / chunks={{ d.chunkCount }}</span></div>
            <div class="row" style="margin-top: 6px">
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
            <select v-model="modelConfig.mode" style="max-width: 180px">
              <option value="LOCAL">LOCAL</option>
              <option value="API">API</option>
            </select>
          </div>
          <div style="margin-top: 8px">
            <input v-model="modelConfig.modelName" placeholder="模型名" />
          </div>
          <div style="margin-top: 8px">
            <input v-model="modelConfig.apiBaseUrl" placeholder="API Base URL（OpenAI兼容）" />
          </div>
          <div style="margin-top: 8px">
            <input v-model="modelConfig.apiKey" placeholder="API Key" />
          </div>
          <button class="primary" style="margin-top: 8px" @click="updateModelConfig">保存配置</button>
        </div>
      </section>

      <section v-else>
        <div class="card">
          <h3>输出中心</h3>
          <div class="list-item" v-for="o in outputs" :key="o.id">
            <div><b>{{ o.name }}</b> <span class="small">{{ o.createdAt }}</span></div>
            <div class="row" style="margin-top: 6px">
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
