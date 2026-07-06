import axios, { type AxiosRequestConfig } from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 30000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (res) => res,
  (err) => {
    const message = err.response?.data?.message || err.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

export const api = {
  async get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T> {
    const res = await http.get<T>(url, config)
    return res.data
  },
  async post<T = any>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    const res = await http.post<T>(url, data, config)
    return res.data
  },
  async put<T = any>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    const res = await http.put<T>(url, data, config)
    return res.data
  },
  async delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T> {
    const res = await http.delete<T>(url, config)
    return res.data
  }
}
