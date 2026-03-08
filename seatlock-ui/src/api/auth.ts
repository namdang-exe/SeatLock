import client from './client'

export interface LoginRequest {
  email: string
  password: string
}

export interface LoginResponse {
  token: string
  expiresAt: string
}

export interface RegisterRequest {
  email: string
  password: string
  phone?: string
}

export interface RegisterResponse {
  userId: string
  email: string
}

export const login = (data: LoginRequest) =>
  client.post<LoginResponse>('/auth/login', data).then(r => r.data)

export const register = (data: RegisterRequest) =>
  client.post<RegisterResponse>('/auth/register', data).then(r => r.data)
