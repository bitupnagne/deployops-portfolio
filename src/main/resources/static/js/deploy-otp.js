(() => {
  'use strict';
  const entry = document.currentScript;
  const flow = entry.dataset.flow;
  const root = new URL('../', entry.src);
  const path = value => new URL(value, root).href;
  const dashboard = () => window.location.assign(path('deploy/dashboard'));
  const cancelLogin = () => document.getElementById('otp-cancel').requestSubmit();

  async function request(endpoint, body) {
    const options = { cache: 'no-store', headers: { Accept: 'application/json' } };
    if (body) {
      options.method = 'POST';
      options.body = body;
      const header = document.querySelector('meta[name="_csrf_header"]').content;
      options.headers[header] = document.querySelector('meta[name="_csrf"]').content;
    }
    let response;
    try {
      response = await fetch(path(endpoint), { ...options, signal: AbortSignal.timeout(12000) });
    } catch {
      throw new Error('서버에 연결할 수 없습니다. 연결 상태를 확인하고 다시 시도해 주세요.');
    }
    if (response.redirected || response.status === 401) {
      window.location.replace(path('login?expired'));
      throw new Error('세션이 만료되었습니다. 다시 로그인해 주세요.');
    }
    if (!response.ok) throw new Error('요청을 처리하지 못했습니다. 다시 시도해 주세요.');
    return response.json();
  }

  async function serverTime(endpoint) {
    const started = Date.now();
    try {
      const { epochMs } = await request(endpoint);
      return epochMs + (Date.now() - started) / 2;
    } catch {
      return undefined;
    }
  }

  async function open(mode, state, endpoint, clock, success, cancel) {
    let result;
    const time = await serverTime(clock);
    await window.SystemOtp.open({
      mode,
      data: { qr: state.qrDataUri, secret: state.secret, account: state.account },
      serverTime: time,
      cancelLabel: cancel === cancelLogin ? '로그아웃' : '취소',
      verify: async code => {
        result = await request(endpoint, new URLSearchParams({ code }));
        if (result.expired) {
          window.location.replace(path('login?expired'));
          throw new Error('세션이 만료되었습니다. 다시 로그인해 주세요.');
        }
        if (!result.ok) throw new Error(result.message || '인증정보가 올바르지 않습니다.');
      },
      onSuccess: () => success(result),
      onCancel: cancel
    });
    const status = document.getElementById('otp-load-status');
    if (status) status.hidden = true;
  }

  async function login() {
    const state = await request('login/otp/state');
    if (!state.pending) {
      window.location.replace(path('login?expired'));
      return;
    }
    await open(state.enrolled ? 'auth' : 'setup', state, 'login/otp/verify',
      'login/otp/server-time', result => window.location.assign(result.redirect || path('deploy/dashboard')), cancelLogin);
  }

  async function registration(inSettings) {
    const state = await request('otp/setup/data');
    if (state.error) {
      window.location.replace(path('login?expired'));
      return;
    }
    if (state.already) {
      if (inSettings) showRegistered();
      else window.location.reload();
      return;
    }
    await open('setup', state, 'otp/confirm', 'otp/server-time',
      result => inSettings ? showToast(result.message || '2차 인증이 등록되었습니다.', 'success') : dashboard(),
      inSettings ? () => {} : dashboard);
  }

  function showRegistered() {
    const modal = document.getElementById('modal-otp');
    const body = document.getElementById('otp-modal-body');
    body.innerHTML = '<p class="notice-box info">이미 2차 인증이 등록되어 있습니다.</p><button type="button" class="btn btn-ghost">2차 인증 해제</button>';
    modal.classList.add('open');
    const button = body.querySelector('button');
    button.onclick = async () => {
      button.disabled = true;
      try {
        const result = await request('otp/off', new URLSearchParams());
        if (!result.ok) throw new Error(result.message || '해제에 실패했습니다.');
        closeModal('modal-otp');
        showToast(result.message || '2차 인증을 해제했습니다.', 'info');
      } catch (error) {
        button.disabled = false;
        showToast(error.message, 'error');
      }
    };
  }

  let loading = false;
  async function start() {
    if (loading) return;
    loading = true;
    try {
      if (flow === 'login') await login();
      else if (flow === 'verify') await open('auth', {}, 'otp/verify-ajax', 'otp/server-time', dashboard, cancelLogin);
      else await registration(flow === 'settings');
    } catch {
      const message = '2차 인증 정보를 불러오지 못했습니다. 다시 시도해 주세요.';
      if (flow === 'settings') showToast(message, 'error');
      else {
        const status = document.getElementById('otp-load-status');
        status.hidden = false;
        const retry = document.createElement('button');
        retry.type = 'button';
        retry.textContent = '다시 시도';
        retry.onclick = start;
        status.replaceChildren(message, retry);
      }
    } finally {
      loading = false;
    }
  }
  if (flow === 'settings') window.openOtpModal = start;
  else start();
})();
