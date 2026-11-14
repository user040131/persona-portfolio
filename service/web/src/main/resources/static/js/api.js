/*
 * /api 호출 도우미. 세션 쿠키 + CSRF 헤더를 붙이고, 오류면 서버 문구로 예외를 던진다 (docs/03 §2.2).
 */
(() => {
  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

  class ApiError extends Error {
    constructor(status, code, message, detail) {
      super(message);
      this.status = status;
      this.code = code;
      this.detail = detail;
    }
  }

  async function api(method, url, body) {
    const headers = { Accept: 'application/json' };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    if (method !== 'GET' && csrfToken && csrfHeader) headers[csrfHeader] = csrfToken;
    let res;
    try {
      res = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch (e) {
      throw new ApiError(0, 'NETWORK', '서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.');
    }
    if (res.status === 401) {
      location.href = '/login';
      throw new ApiError(401, 'UNAUTHORIZED', '로그인이 필요해요.');
    }
    const data = await res.json().catch(() => null);
    if (!res.ok) {
      const err = data?.error ?? {};
      throw new ApiError(res.status, err.code ?? 'ERROR', err.message ?? '잠시 후 다시 시도해 주세요.', err.detail);
    }
    return data;
  }

  const won = (n) => Math.round(n).toLocaleString('ko-KR') + '원';
  const pct = (x, digits = 0) => (x * 100).toFixed(digits).replace(/^-0(\.0+)?$/, '0$1') + '%';

  window.App = { api, ApiError, won, pct };
})();
