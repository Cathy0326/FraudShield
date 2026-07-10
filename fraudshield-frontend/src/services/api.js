import axios from 'axios';

// axios实例：所有请求的基础配置
// Axios instance: shared config for every API call
//
// baseURL留空（相对路径）— 生产环境由nginx同源反向代理转发到后端，浏览器看到的是
// 同一个origin，天然没有CORS问题。本地用`npm run dev`时通过VITE_API_BASE_URL指向后端。
// Empty baseURL (relative paths) — in production nginx reverse-proxies same-origin to the
// backend, so the browser never crosses origins. For local `npm run dev`, set
// VITE_API_BASE_URL to point directly at the backend (see .env.development).
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10000,
});

// 请求拦截器：自动附加JWT Token
// Request interceptor: automatically attach the JWT from localStorage
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 响应拦截器：401时清除登录状态并跳转登录页
// Response interceptor: on 401, clear auth state and redirect to login
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export const login = (username, password) =>
  api.post('/auth/login', { username, password }).then((r) => r.data);

export const getRecentEvents = (limit = 10) =>
  api.get(`/api/risk-events/recent?limit=${limit}`).then((r) => r.data);

export const getEventsByRiskLevel = (level) =>
  api.get(`/api/risk-events?riskLevel=${level}`).then((r) => r.data);

export const getDashboardStats = () =>
  api.get('/api/risk-events/stats').then((r) => r.data);

export const getEventByOrderId = (orderId) =>
  api.get(`/api/risk-events/${orderId}`).then((r) => r.data);

export const triggerTestOrders = () =>
  api.get('/test/send-orders').then((r) => r.data);

export const getAiAnalysis = (orderId) =>
  api.get(`/api/risk-events/${orderId}/ai-analysis`).then((r) => r.data);

export const getUserRiskProfile = (userId) =>
  api.get(`/api/risk-events/user/${userId}/profile`).then((r) => r.data);

export const getReviewQueue = () =>
  api.get('/api/risk-events/review-queue').then((r) => r.data);

export const submitReview = (orderId, decision, notes) =>
  api.post(`/api/risk-events/${orderId}/review`, { decision, notes }).then((r) => r.data);

export const getFinancialImpact = () =>
  api.get('/api/risk-events/financial-impact').then((r) => r.data);

export const getRulePrecision = () =>
  api.get('/api/risk-events/rule-precision').then((r) => r.data);

export default api;
