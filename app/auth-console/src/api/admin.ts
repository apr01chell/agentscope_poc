import { call, http } from './http'

export interface UserSummary {
  id: number
  username: string
  displayName: string
  status: string
  roles: string[]
}

export interface RoleSummary {
  id: number
  code: string
  name: string
  description: string | null
  builtIn: boolean
  userCount: number
  permissions: string[]
}

export interface PermissionSummary {
  id: number
  code: string
  name: string
  resource: string
  action: string
}

export interface ClientSummary {
  id: string
  clientId: string
  clientName: string
  redirectUris: string[]
  scopes: string[]
  requireProofKey: boolean
}

export interface RotatedSecret {
  clientId: string
  clientSecret: string
}

export const adminApi = {
  users: (keyword?: string) =>
    call<UserSummary[]>(http.get('/auth/admin/users', { params: { keyword } })),

  createUser: (body: { username: string; password: string; displayName: string; roleIds: number[] }) =>
    call<UserSummary>(http.post('/auth/admin/users', body)),

  updateUser: (id: number, body: { displayName?: string; password?: string; status?: string }) =>
    call<UserSummary>(http.patch(`/auth/admin/users/${id}`, body)),

  setUserRoles: (id: number, roleIds: number[]) =>
    call<UserSummary>(http.put(`/auth/admin/users/${id}/roles`, { roleIds })),

  deleteUser: (id: number) => call<null>(http.delete(`/auth/admin/users/${id}`)),

  roles: () => call<RoleSummary[]>(http.get('/auth/admin/roles')),

  createRole: (body: { code: string; name: string; description?: string; permissionIds: number[] }) =>
    call<RoleSummary>(http.post('/auth/admin/roles', body)),

  setRolePermissions: (id: number, permissionIds: number[]) =>
    call<RoleSummary>(http.put(`/auth/admin/roles/${id}/permissions`, { permissionIds })),

  deleteRole: (id: number) => call<null>(http.delete(`/auth/admin/roles/${id}`)),

  permissions: () => call<PermissionSummary[]>(http.get('/auth/admin/permissions')),

  clients: () => call<ClientSummary[]>(http.get('/auth/admin/clients')),

  createClient: (body: {
    clientId: string
    clientSecret: string
    clientName: string
    redirectUris: string[]
    scopes: string[]
  }) => call<RotatedSecret>(http.post('/auth/admin/clients', body)),

  rotateSecret: (id: string) =>
    call<RotatedSecret>(http.post(`/auth/admin/clients/${id}/rotate-secret`)),

  deleteClient: (id: string) => call<null>(http.delete(`/auth/admin/clients/${id}`)),
}
