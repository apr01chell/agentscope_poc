/**
 * 校验回跳地址。
 *
 * 只接受站内绝对路径：`//evil.com` 与 `https://evil.com` 在浏览器眼里都是外站，直接
 * `router.replace(redirect)` 就是一个开放重定向漏洞。登录页与路由守卫共用这一份实现，
 * 避免「守卫拦了、登录页没拦」这种只在一侧出现的缺口。
 */
export function safeRedirect(value: unknown, fallback = '/home'): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//'))
    return fallback
  return value
}
