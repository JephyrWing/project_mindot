import test, { beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import axios, { AxiosError } from 'axios'
import { createHttpClient } from './httpClient.js'
import { createAuthApi } from '../auth/authApi.js'
import {
  clearAccessToken,
  getAccessToken,
  setAccessToken,
} from '../auth/tokenStorage.js'

class MemoryStorage {
  constructor() {
    this.values = new Map()
  }

  getItem(key) {
    return this.values.has(key) ? this.values.get(key) : null
  }

  setItem(key, value) {
    this.values.set(key, String(value))
  }

  removeItem(key) {
    this.values.delete(key)
  }

  clear() {
    this.values.clear()
  }
}

globalThis.sessionStorage = new MemoryStorage()
globalThis.localStorage = new MemoryStorage()

beforeEach(() => {
  sessionStorage.clear()
  localStorage.clear()
})

test('access token is stored only in sessionStorage', () => {
  localStorage.setItem('mindot.rememberedEmail', 'user@example.com')

  setAccessToken('access-token')

  assert.equal(getAccessToken(), 'access-token')
  assert.equal(localStorage.getItem('mindot.accessToken'), null)
  assert.equal(
    localStorage.getItem('mindot.rememberedEmail'),
    'user@example.com',
  )
})

test('successful login stores the returned access token', async () => {
  const authApi = createAuthApi({
    post: async () => ({ data: { accessToken: 'login-access-token' } }),
  })

  await authApi.login({
    email: 'user@example.com',
    password: 'password123',
  })

  assert.equal(getAccessToken(), 'login-access-token')
  assert.equal(localStorage.getItem('mindot.accessToken'), null)
})

test('protected requests receive Bearer token but auth requests do not', async () => {
  setAccessToken('access-token')
  const seenHeaders = []
  const apiClient = axios.create({
    adapter: async (config) => {
      seenHeaders.push(config.headers.Authorization)
      return response(config, 200, {})
    },
  })
  const client = createHttpClient({ apiClient })

  await client.get('/api/protected')
  await client.post('/api/auth/login', {})

  assert.deepEqual(seenHeaders, ['Bearer access-token', undefined])
})

test('concurrent 401 responses share one refresh and retry once', async () => {
  setAccessToken('expired-token')
  let apiCalls = 0
  let refreshCalls = 0

  const apiClient = axios.create({
    adapter: async (config) => {
      apiCalls += 1
      if (config.headers.Authorization !== 'Bearer renewed-token') {
        throw unauthorized(config)
      }
      return response(config, 200, { ok: true })
    },
  })
  const refreshClient = axios.create({
    adapter: async (config) => {
      refreshCalls += 1
      await new Promise((resolve) => setTimeout(resolve, 10))
      return response(config, 200, { accessToken: 'renewed-token' })
    },
  })
  const client = createHttpClient({ apiClient, refreshClient })

  const [first, second] = await Promise.all([
    client.get('/api/protected/first'),
    client.get('/api/protected/second'),
  ])

  assert.equal(first.data.ok, true)
  assert.equal(second.data.ok, true)
  assert.equal(refreshCalls, 1)
  assert.equal(apiCalls, 4)
  assert.equal(getAccessToken(), 'renewed-token')
})

test('refresh failure clears access token without retrying refresh', async () => {
  setAccessToken('expired-token')
  let refreshCalls = 0
  const apiClient = axios.create({
    adapter: async (config) => {
      throw unauthorized(config)
    },
  })
  const refreshClient = axios.create({
    adapter: async (config) => {
      refreshCalls += 1
      throw unauthorized(config)
    },
  })
  const client = createHttpClient({ apiClient, refreshClient })

  await assert.rejects(client.get('/api/protected'))

  assert.equal(refreshCalls, 1)
  assert.equal(getAccessToken(), null)
})

test('clearing access token leaves remembered email intact', () => {
  localStorage.setItem('mindot.rememberedEmail', 'user@example.com')
  setAccessToken('access-token')

  clearAccessToken()

  assert.equal(getAccessToken(), null)
  assert.equal(
    localStorage.getItem('mindot.rememberedEmail'),
    'user@example.com',
  )
})

test('logout clears access token even when the API request fails', async () => {
  setAccessToken('access-token')
  const authApi = createAuthApi({
    post: async () => {
      throw new Error('network failure')
    },
  })

  await assert.rejects(authApi.logout())

  assert.equal(getAccessToken(), null)
})

const response = (config, status, data) => ({
  config,
  status,
  statusText: status === 200 ? 'OK' : 'Unauthorized',
  headers: {},
  data,
})

const unauthorized = (config) => new AxiosError(
  'Unauthorized',
  AxiosError.ERR_BAD_REQUEST,
  config,
  null,
  response(config, 401, {}),
)
