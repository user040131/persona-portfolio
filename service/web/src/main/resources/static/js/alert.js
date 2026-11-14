/* 리밸런싱 제안 화면 (docs/03 §7.1 /alerts/{id}, §7.2 #20·21, 01 FR-ALT-08·09). */
(() => {
  const { api, won, pct } = App;
  const $ = (x) => document.getElementById(x);
  const id = $('al').dataset.id;
  const LABEL = { stock: '주식', bond: '채권', cash: '현금' };

  const el = (tag, attrs = {}, ...children) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else if (v !== null && v !== undefined && v !== false) e.setAttribute(k, v === true ? '' : v);
    }
    e.append(...children.filter((c) => c !== null && c !== undefined));
    return e;
  };

  async function load() {
    try {
      render(await api('GET', `/api/alerts/${id}/suggestion`));
    } catch (e) {
      $('loading').hidden = true;
      $('error').textContent = e.status === 404 ? '알림을 찾을 수 없어요.' : e.message;
      $('error').hidden = false;
    }
  }

  function render(s) {
    $('loading').hidden = true;
    $('content').hidden = false;
    $('alert-title').textContent = s.alert.title;
    $('alert-title').className = `alert-title ${s.alert.type}`;
    $('alert-text').textContent = s.alert.text;
    $('help').textContent = s.help;
    $('meta').textContent = `${s.asOf} 종가 기준 · 지금 총액 ${won(s.totalValue)}`;

    $('trade-rows').replaceChildren(...(s.trades.length ? s.trades.map((t) => el('tr', {},
      el('td', {}, t.name, t.quantity != null ? el('span', { class: 'muted small' }, ` ${t.asset}`) : null),
      el('td', { class: `side-${t.side}` }, t.sideLabel),
      el('td', { class: 'num' }, t.quantity != null ? `${t.quantity.toLocaleString('ko-KR')}주` : '-'),
      el('td', { class: 'num' }, won(t.amount)))) : [el('tr', {}, el('td', { colspan: 4, class: 'muted' }, '사고팔 것이 없어요.'))]));

    $('compare').replaceChildren(
      el('span', { class: 'head' }), el('span', { class: 'head' }, '지금'), el('span', { class: 'head' }, '거래 후'),
      el('span', { class: 'head' }, '변화'),
      ...['stock', 'bond', 'cash'].flatMap((k) => {
        const d = s.after.byAsset[k] - s.before[k];
        return [el('span', {}, LABEL[k]), el('span', {}, pct(s.before[k])), el('strong', {}, pct(s.after.byAsset[k])),
          el('span', { class: 'muted' }, Math.abs(d) < 0.005 ? '그대로' : `${d > 0 ? '+' : ''}${(d * 100).toFixed(0)}%p`)];
      }));
    $('target-rows').replaceChildren(...s.targets.map((t) => el('tr', {},
      el('td', {}, t.name), el('td', { class: 'num' }, pct(t.target, 1)), el('td', { class: 'num' }, pct(t.after, 1)))));

    $('apply-btn').hidden = !s.canApply;
    $('dismiss-btn').hidden = !s.canApply || s.alert.status === 'DISMISSED';
    $('closed').hidden = s.canApply;
  }

  $('apply-btn').addEventListener('click', async (e) => {
    if (!confirm('이 제안대로 사고팔았다고 기록할까요? 보유 수량·채권·현금과 목표 비중이 바뀌어요.')) return;
    e.target.disabled = true;
    try {
      await api('POST', `/api/alerts/${id}/apply`);
      location.href = '/portfolio';
    } catch (err) {
      $('apply-error').textContent = err.message;
      $('apply-error').hidden = false;
      e.target.disabled = false;
    }
  });

  $('dismiss-btn').addEventListener('click', async (e) => {
    e.target.disabled = true;
    try {
      await api('POST', `/api/alerts/${id}/dismiss`);
      location.href = '/portfolio';
    } catch (err) {
      $('apply-error').textContent = err.message;
      $('apply-error').hidden = false;
      e.target.disabled = false;
    }
  });

  load();
})();
