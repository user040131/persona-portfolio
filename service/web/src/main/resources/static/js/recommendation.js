/*
 * 추천 결과 화면 (docs/03 §7.1, §7.3 #10~15).
 * 원그래프: 바깥 고리 = 주식·채권·현금, 안쪽 고리 = 주식 안 종목.
 * 비중 방식 탭은 화면에서만 미리 보고, "이 방식으로 정하기"를 눌러야 새 버전으로 저장된다.
 */
(() => {
  const { api, won, pct } = App;
  const root = document.getElementById('reco');
  const id = root.dataset.id;
  const $ = (x) => document.getElementById(x);

  const COLORS = { stock: '#2f6fde', bond: '#4f9a5b', cash: '#c9a227' };
  const METHODS = ['EQUAL', 'INVERSE_VOL', 'MIN_VARIANCE'];

  let data = null;       // 현재 보고 있는 버전
  let preview = null;    // 탭에서 보고 있는 방식
  let chart = null;
  const opened = new Set();   // 근거를 펼친 종목

  const el = (tag, attrs = {}, ...children) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else if (v !== null && v !== undefined && v !== false) e.setAttribute(k, v === true ? '' : v);
    }
    e.append(...children.filter((c) => c !== null && c !== undefined));
    return e;
  };

  const showError = (msg) => {
    $('error').textContent = msg;
    $('error').hidden = !msg;
  };

  const editable = () => data.version === data.latestVersion && !data.purchased;

  // ------------------------------------------------------------ 불러오기·수정

  async function load(version) {
    try {
      const q = version ? `?version=${version}` : '';
      data = await api('GET', `/api/recommendations/${id}${q}`);
      preview = data.methodSelected;
      render();
    } catch (e) {
      $('loading').hidden = true;
      showError(e.status === 404 ? '추천 결과를 찾을 수 없어요.' : e.message);
    }
  }

  async function edit(body, busyButton) {
    showError('');
    const label = busyButton?.textContent;
    document.querySelectorAll('#content button').forEach((b) => { b.disabled = true; });
    if (busyButton) busyButton.textContent = '다시 계산하는 중…';
    try {
      data = await api('POST', `/api/recommendations/${id}/versions`, body);
      preview = data.methodSelected;
      render();
    } catch (e) {
      showError(e.message);
      render();
    } finally {
      if (busyButton) busyButton.textContent = label;
    }
  }

  // ------------------------------------------------------------ 그리기

  function render() {
    $('loading').hidden = true;
    $('content').hidden = false;
    document.querySelectorAll('#content button').forEach((b) => { b.disabled = false; });
    renderHead();
    renderChart();
    renderStocks();
    renderSafe();
    renderCombined();
    renderPurchase();
  }

  function renderHead() {
    const sel = $('version-select');
    sel.replaceChildren(...Array.from({ length: data.latestVersion }, (_, i) => i + 1).reverse()
      .map((v) => el('option', { value: v, selected: v === data.version },
        `v${v}${v === data.latestVersion ? ' (최신)' : ''}${v === data.purchasedVersion ? ' · 매수 완료' : ''}`)));
    sel.onchange = () => load(Number(sel.value));

    $('meta').textContent = `${data.profile.gradeLabel} · 투자 금액 ${won(data.investAmount)} · ${data.asOf} 종가 기준`;

    const old = $('old-version');
    old.hidden = data.version === data.latestVersion;
    old.textContent = '지난 버전을 보고 있어요. 수정과 매수 완료는 최신 버전에서만 할 수 있어요.';

    $('warnings').replaceChildren(...data.warnings.filter((w) => w.text)
      .map((w) => el('p', { class: 'notice warn' }, w.text)));
    $('alloc-reasons').replaceChildren(...data.allocationReasons.map((t) => el('li', {}, t)));
  }

  /** 미리 보는 방식의 주문. 방식마다 1주 단위로 맞춘 금액이 조금씩 달라 현금도 다시 센다. */
  function previewItems() {
    return [...(data.methods[preview]?.items ?? [])].sort((a, b) => b.weight - a.weight || a.code.localeCompare(b.code));
  }

  function amounts(items) {
    const stock = items.reduce((s, i) => s + i.spend, 0);
    const bond = data.allocation.bondAmount;
    return { stock, bond, cash: data.investAmount - stock - bond };
  }

  function renderChart() {
    const items = previewItems();
    const a = amounts(items);
    const total = data.investAmount;
    const L = data.labels.assets;

    $('asset-list').replaceChildren(
      ...['stock', 'bond', 'cash'].map((k) => el('li', {},
        el('span', { class: 'swatch', style: `background:${COLORS[k]}` }),
        el('span', { class: 'asset-name' }, L[k]),
        el('strong', {}, pct(a[k] / total)),
        el('span', { class: 'muted' }, won(a[k])))),
      el('li', { class: 'muted small' },
        `${data.allocation.lookback.start.slice(0, 4)}년 이후 시장 기준 ` +
        `최대 하락폭 ${pct(data.allocation.expectedMdd, 1)}`));

    const shades = items.map((_, i) => `hsl(217, 65%, ${32 + (i * 40) / Math.max(items.length, 1)}%)`);
    const config = {
      type: 'doughnut',
      data: {
        labels: [L.stock, L.bond, L.cash],
        datasets: [
          { label: '자산', data: [a.stock, a.bond, a.cash], backgroundColor: [COLORS.stock, COLORS.bond, COLORS.cash] },
          {
            label: '종목',
            data: [...items.map((i) => i.spend), a.bond, a.cash],
            backgroundColor: [...shades, 'rgba(0,0,0,0.04)', 'rgba(0,0,0,0.04)'],
            names: [...items.map((i) => data.names[i.code] ?? i.code), L.bond, L.cash],
            shares: [...items.map((i) => i.shares), null, null],
          },
        ],
      },
      options: {
        cutout: '35%',
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (ctx) => {
                const ds = ctx.dataset;
                const name = ds.names ? ds.names[ctx.dataIndex] : ctx.chart.data.labels[ctx.dataIndex];
                const shares = ds.shares?.[ctx.dataIndex];
                return `${name} ${pct(ctx.raw / total, 1)} · ${won(ctx.raw)}${shares ? ` · ${shares}주` : ''}`;
              },
            },
          },
        },
        onClick: (_, els) => {
          const hit = els.find((e) => e.datasetIndex === 1 && e.index < items.length);
          if (hit) toggleReasons(items[hit.index].code, true);
        },
      },
    };
    if (!window.Chart) return;   // CDN을 못 읽으면 표만 보여준다
    if (chart) chart.destroy();
    chart = new Chart($('chart'), config);
  }

  function renderStocks() {
    const items = previewItems();
    const byCode = Object.fromEntries(data.stocks.map((s) => [s.code, s]));
    const can = editable();

    $('method-tabs').replaceChildren(...METHODS.map((m) => el('button', {
      type: 'button', role: 'tab', class: `tab${m === preview ? ' current' : ''}`, 'aria-selected': m === preview,
      onclick: () => { preview = m; renderChart(); renderStocks(); },
    }, data.labels.methods[m], m === data.methodSelected ? ' ✓' : '')));

    const fallback = data.methods[preview]?.fallback;
    $('method-note').textContent = preview === data.methodSelected
      ? `지금 고른 방식이에요.${fallback ? ' 과거 자료가 부족해 변동성 역수가중으로 대신 계산했어요.' : ''}`
      : '미리 보기예요. 매수 완료는 ✓ 표시된 방식으로 기록돼요.';
    $('method-apply').hidden = !(can && preview !== data.methodSelected);
    $('method-apply-btn').textContent = `'${data.labels.methods[preview]}' 방식으로 정하기`;
    $('method-apply-btn').onclick = (e) => edit({ method: preview }, e.target);

    $('stock-count').textContent = items.length ? `${items.length}종목` : '';
    $('no-stocks').hidden = items.length > 0;
    const rows = [];
    for (const it of items) {
      const s = byCode[it.code];
      const name = data.names[it.code] ?? it.code;
      rows.push(el('tr', { class: 'stock-row', onclick: (e) => { if (e.target.tagName !== 'BUTTON') toggleReasons(it.code); } },
        el('td', {},
          el('span', { class: 'toggle' }, opened.has(it.code) ? '▾ ' : '▸ '),
          name, ' ', el('span', { class: 'muted' }, `${it.code} · ${s?.sector ?? ''}`)),
        el('td', { class: 'num' }, pct(it.weight, 1)),
        el('td', { class: 'num' }, `${it.shares}주`),
        el('td', { class: 'num' }, won(it.spend)),
        el('td', { class: 'num' }, can ? el('button', {
          type: 'button', class: 'small',
          onclick: (e) => {
            if (confirm(`${name}을(를) 빼고 다음 순위 종목으로 다시 계산할까요?`)) edit({ removedCodes: [it.code] }, e.target);
          },
        }, '빼기') : null)));
      if (opened.has(it.code) && s) {
        rows.push(el('tr', { class: 'reason-row' }, el('td', { colspan: 5 },
          el('ul', { class: 'reasons' }, ...s.reasons.map((r) => el('li', { class: `reason ${r.type.toLowerCase()}` }, r.text))))));
      }
    }
    $('stock-rows').replaceChildren(...rows);

    const removed = data.edits.removedCodes;
    $('removed').textContent = removed.length
      ? `직접 뺀 종목: ${removed.map((c) => data.names[c] ?? c).join(', ')}`
      : '';
  }

  function toggleReasons(code, open) {
    if (open || !opened.has(code)) opened.add(code); else opened.delete(code);
    renderStocks();
  }

  function renderSafe() {
    const range = $('safe-range');
    const min = data.profile.minCash;
    const current = Math.round((data.allocation.bond + data.allocation.cash) * 100) / 100;
    range.min = min;
    range.value = current;
    const show = () => { $('safe-value').textContent = pct(Number(range.value)); };
    range.oninput = show;
    show();
    const limit = data.profile.lossLimit;
    $('safe-help').textContent =
      `지금 안전자산은 ${pct(current)}예요. 현금 최소 비중(${pct(min)}) 아래로는 내릴 수 없어요.` +
      (limit != null ? ` 안전자산을 줄이면 말씀하신 손실 한도(${pct(limit)})를 넘을 수 있어요.` : '');
    const can = editable();
    $('safe-card').classList.toggle('disabled', !can);
    range.disabled = !can;
    $('safe-apply').disabled = !can;
    $('safe-apply').onclick = (e) => edit({ safeRatio: Number(range.value) }, e.target);
    $('safe-reset').hidden = !(can && data.edits.safeRatio != null);
    $('safe-reset').onclick = (e) => edit({ safeRatio: -1 }, e.target);
  }

  function renderCombined() {
    const c = data.combined;
    const L = data.labels.assets;
    $('combined-assets').replaceChildren(
      ...['stock', 'bond', 'cash'].map((k) => el('li', {},
        el('span', { class: 'swatch', style: `background:${COLORS[k]}` }), `${L[k]} `, el('strong', {}, pct(c.byAsset[k])))),
      el('li', { class: 'muted' }, `최대 하락폭 ${pct(c.expectedMdd, 1)}`));
    const rows = Object.entries(c.targetWeights).sort((a, b) => b[1] - a[1]).map(([k, w]) => {
      const name = k === 'BOND' ? L.bond : k === 'CASH' ? L.cash : `${data.names[k] ?? k}`;
      return el('tr', {}, el('td', {}, name, k.length === 6 ? el('span', { class: 'muted' }, ` ${k}`) : null),
        el('td', { class: 'num' }, pct(w, 1)));
    });
    $('combined-rows').replaceChildren(...rows);
  }

  function renderPurchase() {
    const btn = $('purchase-btn');
    const state = $('purchase-state');
    btn.hidden = !data.canPurchase;
    state.hidden = data.canPurchase;
    $('portfolio-link').hidden = !data.purchased;
    if (data.purchased) {
      state.textContent = `v${data.purchasedVersion}을(를) 매수 완료로 기록했어요.`;
    } else if (data.version !== data.latestVersion) {
      state.textContent = '최신 버전에서 매수 완료할 수 있어요.';
    } else {
      state.textContent = '더 최근에 받은 추천이 있어요. 최근 추천에서 매수 완료해 주세요.';
    }
    btn.onclick = async () => {
      const method = data.labels.methods[data.methodSelected];
      if (!confirm(`v${data.version} (${method}) 내용대로 샀다고 기록할까요?`)) return;
      $('purchase-error').hidden = true;
      btn.disabled = true;
      try {
        await api('POST', `/api/recommendations/${id}/purchase`, { version: data.version });
        await load();
      } catch (e) {
        $('purchase-error').textContent = e.message;
        $('purchase-error').hidden = false;
        btn.disabled = false;
      }
    };
  }

  // ------------------------------------------------------------ 왜 없나요

  let timer = null;
  $('whynot-search').addEventListener('input', (e) => {
    clearTimeout(timer);
    const q = e.target.value.trim();
    if (!q) { $('whynot-results').replaceChildren(); return; }
    timer = setTimeout(async () => {
      const list = await api('GET', `/api/stocks?q=${encodeURIComponent(q)}&limit=8`).catch(() => []);
      $('whynot-results').replaceChildren(...list.map((s) => el('li', {}, el('button', {
        type: 'button',
        onclick: () => whyNot(s.code),
      }, `${s.name} `, el('span', { class: 'muted' }, `${s.code} · ${s.sector}`)))));
    }, 250);
  });

  async function whyNot(code) {
    const box = $('whynot-answer');
    $('whynot-results').replaceChildren();
    try {
      const r = await api('GET', `/api/recommendations/${id}/why-not/${encodeURIComponent(code)}`);
      box.replaceChildren(el('strong', {}, `${r.name} `), r.text);
      box.className = `notice${r.inPortfolio ? '' : ' warn'}`;
    } catch (e) {
      box.textContent = e.message;
      box.className = 'notice error';
    }
    box.hidden = false;
  }

  // ------------------------------------------------------------ 과거 성과 카드 (FR-REC-14)

  async function historyCard() {
    try {
      const c = await api('GET', `/api/recommendations/${id}/history-card`);
      $('history-text').textContent = `${c.start} ~ ${c.end}`;
      const row = (k, v) => [el('dt', {}, k), el('dd', {}, v)];
      $('history-stats').replaceChildren(
        ...row('누적 수익률', `${c.cumulative_return >= 0 ? '+' : ''}${pct(c.cumulative_return, 1)}`),
        ...row('가장 크게 떨어졌을 때', pct(c.max_drawdown, 1)),
        ...row('가장 나빴던 해', `${c.worst_year.year}년 ${pct(c.worst_year.return, 1)}`));
      $('history-stats').hidden = false;
      $('history-note').hidden = false;
    } catch (e) {
      $('history-text').textContent = `과거 성과를 계산하지 못했어요: ${e.message}`;
    }
  }

  load();
  historyCard();
})();
