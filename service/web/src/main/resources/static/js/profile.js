/* 프로필 화면의 "추천 받기" (docs/03 §7.2 #10). */
(() => {
  const box = document.getElementById('recommend');
  if (!box) return;
  const btn = document.getElementById('recommend-btn');
  const input = document.getElementById('invest-amount');
  const errorBox = document.getElementById('recommend-error');
  const hasPending = box.dataset.pending !== undefined && box.dataset.pending !== '';
  const min = Number(box.dataset.min);

  const digits = (s) => Number(String(s).replace(/[^0-9]/g, ''));
  input?.addEventListener('input', () => {
    const n = digits(input.value);
    input.value = n ? n.toLocaleString('ko-KR') : '';
  });

  const showError = (msg) => {
    errorBox.textContent = msg;
    errorBox.hidden = !msg;
  };

  btn.addEventListener('click', async () => {
    let body = {};
    if (!hasPending) {
      const amount = digits(input.value);
      if (!amount || amount < min) {
        showError(`투자 금액은 ${min.toLocaleString('ko-KR')}원 이상이어야 해요.`);
        return;
      }
      body = { investAmount: amount };
    }
    showError('');
    btn.disabled = true;
    btn.textContent = '계산하는 중…';
    try {
      const r = await App.api('POST', '/api/recommendations', body);
      location.href = `/recommendations/${r.recommendationId}`;
    } catch (e) {
      showError(e.message);
      btn.disabled = false;
      btn.textContent = '추천 받기';
    }
  });
})();
