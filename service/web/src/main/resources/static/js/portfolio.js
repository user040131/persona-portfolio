/*
 * 내 포트폴리오 (docs/03 §7.1, §7.3 #16~18, 01 FR-PF-02~05).
 * 원그래프: 바깥 고리 = 주식·채권·현금, 안쪽 고리 = 종목. 종목 조각을 누르면 상세가 나온다.
 * "직접 고치기"에서 수량·채권·현금을 바꾸면 그래프와 비중을 바로 다시 그린다(저장 전 미리 보기).
 */
(() => {
  const { api, won, pct } = App;
  const $ = (x) => document.getElementById(x);
  const root = $('pf');
  const min = Number(root.dataset.min);

  const COLORS = { stock: '#2f6fde', bond: '#4f9a5b', cash: '#c9a227' };
  const LABEL = { stock: '주식', bond: '채권', cash: '현금' };
  const BAND = 0.05;   // 목표와 5%p 넘게 벌어지면 표시 (리밸런싱 알림 기준과 같음)

  let data = null;     // 서버 평가 결과
  let draft = null;    // 화면 상태: {stocks: [...], bondValue, cash}
  let editing = false;
  let selected = null;
  let chart = null;

  const el = (tag, attrs = {}, ...children) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else if (v !== null && v !== undefined && v !== false) e.setAttribute(k, v === true ? '' : v);
    }
    e.append(...children.filter((c) => c !== null && c !== undefined));
    return e;
  };
  const digits = (s) => Number(String(s).replace(/[^0-9]/g, ''));
  const money = (input) => input.addEventListener('input', () => {
    const n = digits(input.value);
    input.value = n ? n.toLocaleString('ko-KR') : '';
  });

  // ------------------------------------------------------------ 불러오기·저장

  async function load() {
    try {
      setData(await api('GET', '/api/portfolio'));
    } catch (e) {
      $('loading').hidden = true;
      $('error').textContent = e.message;
      $('error').hidden = false;
    }
  }

  function setData(d) {
    data = d;
    draft = fromServer();
    editing = false;
    render();
  }

  const fromServer = () => ({
    stocks: data.stocks.map((s) => ({ ...s, origQuantity: s.quantity, origAvg: s.avgPrice })),
    bondValue: data.bond.value,
    cash: data.cash.value,
  });

  async function save() {
    const holdings = [];
    const original = Object.fromEntries(data.stocks.map((s) => [s.code, s]));
    for (const s of draft.stocks) {
      const o = original[s.code];
      if (!o || o.quantity !== s.quantity || o.avgPrice !== s.avgPrice) {
        holdings.push({ code: s.code, quantity: s.quantity, avgPrice: s.avgPrice ?? 0 });
      }
    }
    for (const o of data.stocks) {
      if (!draft.stocks.find((s) => s.code === o.code)) holdings.push({ code: o.code, quantity: 0 });
    }
    const body = { holdings };
    if (draft.bondValue !== data.bond.value) body.bondAmount = draft.bondValue;
    if (draft.cash !== data.cash.value) body.cash = draft.cash;
    $('save-btn').disabled = true;
    try {
      setData(await api('PATCH', '/api/portfolio', body));
      $('edit-error').hidden = true;
    } catch (e) {
      $('edit-error').textContent = e.message;
      $('edit-error').hidden = false;
    } finally {
      $('save-btn').disabled = false;
    }
  }

  // ------------------------------------------------------------ 계산 (서버와 같은 식: 수량 × 종가)

  function computed() {
    const stocks = draft.stocks.map((s) => ({ ...s, value: Math.round(s.quantity * (s.close ?? 0)) }));
    const stock = stocks.reduce((a, s) => a + s.value, 0);
    const total = stock + draft.bondValue + draft.cash;
    const w = (v) => (total > 0 ? v / total : 0);
    stocks.forEach((s) => {
      s.weight = w(s.value);
      s.weightInStock = stock > 0 ? s.value / stock : 0;
    });
    stocks.sort((a, b) => b.value - a.value || a.code.localeCompare(b.code));
    const sectors = {};
    stocks.forEach((s) => { sectors[s.sector] = (sectors[s.sector] ?? 0) + s.weightInStock; });
    return {
      stocks, total,
      value: { stock, bond: draft.bondValue, cash: draft.cash },
      weight: { stock: w(stock), bond: w(draft.bondValue), cash: w(draft.cash) },
      sectors: Object.entries(sectors).sort((a, b) => b[1] - a[1]),
    };
  }

  // ------------------------------------------------------------ 그리기

  function renderAlerts() {
    const list = data.alerts ?? [];
    $('alerts-card').hidden = list.length === 0;
    $('alert-count').textContent = `${list.length}건`;
    $('alert-list').replaceChildren(...list.map((a) => el('li', { class: `alert-item ${a.type}` },
      el('div', {}, el('span', { class: 'alert-title' }, a.title), ' ', a.text),
      el('div', { class: 'actions' },
        el('a', { class: 'button small', href: `/alerts/${a.alertId}` }, '어떻게 고칠까요?'),
        el('button', {
          type: 'button', class: 'small',
          onclick: async (e) => {
            e.target.disabled = true;
            try {
              await api('POST', `/api/alerts/${a.alertId}/dismiss`);
              data.alerts = data.alerts.filter((x) => x.alertId !== a.alertId);
              renderAlerts();
            } catch (err) {
              e.target.disabled = false;
              alert(err.message);
            }
          },
        }, '닫기')))));
  }

  function render() {
    $('loading').hidden = true;
    $('content').hidden = false;
    renderAlerts();
    const c = computed();
    $('total').textContent = won(c.total);
    $('meta').textContent = ` · ${data.asOf} 종가 기준` + (data.purchasedAt ? ` · 매수 완료 ${data.purchasedAt}` : '');
    $('edit-banner').hidden = !editing;
    renderAssets(c);
    renderChart(c);
    renderDetail(c);
    renderSectors(c);
    renderHoldings(c);
    $('edit-btn').hidden = editing;
    $('save-btn').hidden = !editing;
    $('cancel-btn').hidden = !editing;
    $('add-stock').hidden = !editing;
  }

  function renderAssets(c) {
    const t = data.targetByAsset;
    $('asset-list').replaceChildren(...['stock', 'bond', 'cash'].map((k) => {
      const gap = t ? c.weight[k] - t[k] : 0;
      const off = t && Math.abs(gap) > BAND;
      return el('li', { class: off ? 'off-target' : null },
        el('span', { class: 'swatch', style: `background:${COLORS[k]}` }),
        el('span', { class: 'asset-name' }, LABEL[k]),
        el('strong', {}, pct(c.weight[k])),
        el('span', { class: 'muted' }, won(c.value[k])),
        t ? el('span', { class: 'muted small' }, `목표 ${pct(t[k])}${off ? ` · ${gap > 0 ? '+' : ''}${(gap * 100).toFixed(0)}%p` : ''}`) : null);
    }));
  }

  function renderChart(c) {
    if (!window.Chart) return;   // CDN을 못 읽으면 표만 보여준다
    const shades = c.stocks.map((_, i) => `hsl(217, 65%, ${32 + (i * 40) / Math.max(c.stocks.length, 1)}%)`);
    const config = {
      type: 'doughnut',
      data: {
        labels: [LABEL.stock, LABEL.bond, LABEL.cash],
        datasets: [
          { data: [c.value.stock, c.value.bond, c.value.cash], backgroundColor: [COLORS.stock, COLORS.bond, COLORS.cash] },
          {
            data: [...c.stocks.map((s) => s.value), c.value.bond, c.value.cash],
            backgroundColor: [...c.stocks.map((s, i) => (s.code === selected ? '#f0883e' : shades[i])),
              'rgba(0,0,0,0.04)', 'rgba(0,0,0,0.04)'],
            names: [...c.stocks.map((s) => s.name), LABEL.bond, LABEL.cash],
            shares: [...c.stocks.map((s) => s.quantity), null, null],
          },
        ],
      },
      options: {
        cutout: '35%',
        animation: { duration: editing ? 0 : 400 },
        plugins: {
          legend: { display: false },
          tooltip: {
            callbacks: {
              label: (ctx) => {
                const ds = ctx.dataset;
                const name = ds.names ? ds.names[ctx.dataIndex] : ctx.chart.data.labels[ctx.dataIndex];
                const shares = ds.shares?.[ctx.dataIndex];
                return `${name} ${pct(c.total > 0 ? ctx.raw / c.total : 0, 1)} · ${won(ctx.raw)}${shares ? ` · ${shares}주` : ''}`;
              },
            },
          },
        },
        onClick: (_, els) => {
          const hit = els.find((e) => e.datasetIndex === 1 && e.index < c.stocks.length);
          if (hit) select(c.stocks[hit.index].code);
        },
      },
    };
    if (chart) {
      chart.data = config.data;
      chart.options = config.options;
      chart.update();
    } else {
      chart = new Chart($('chart'), config);
    }
  }

  function select(code) {
    selected = selected === code ? null : code;
    render();
    if (selected) $('detail').scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }

  function renderDetail(c) {
    const box = $('detail');
    const s = c.stocks.find((x) => x.code === selected);
    box.hidden = !s;
    if (!s) return;
    const ret = s.avgPrice && s.close ? s.close / s.avgPrice - 1 : null;
    const row = (k, v) => [el('dt', {}, k), el('dd', {}, v)];
    box.replaceChildren(
      el('div', { class: 'reco-head' },
        el('h3', {}, s.name, ' ', el('span', { class: 'muted' }, `${s.code} · ${s.sector}`)),
        el('button', { type: 'button', class: 'small', onclick: () => select(s.code) }, '닫기')),
      el('dl', { class: 'settings' },
        ...row('평가금액', won(s.value)),
        ...row('수량', `${s.quantity.toLocaleString('ko-KR')}주`),
        ...row('현재가', s.close ? won(s.close) : '거래 가격 없음'),
        ...row('평균 매입가', s.avgPrice ? won(s.avgPrice) : '모름 (직접 고치기에서 넣을 수 있어요)'),
        ...row('수익률', ret == null ? '-' : el('span', { class: ret >= 0 ? 'up' : 'down' }, `${ret >= 0 ? '+' : ''}${(ret * 100).toFixed(1)}%`)),
        ...row('현재 비중', `전체의 ${pct(s.weight, 1)} · 주식 안 ${pct(s.weightInStock, 1)}`),
        ...row('목표 비중', s.targetWeight == null ? '목표 없음' : `전체의 ${pct(s.targetWeight, 1)}`),
        ...(s.delistRules?.length ? row('주의', el('span', { class: 'warn-text' }, `상장폐지 위험 기준에 걸려요 (${s.delistRules.join(', ')})`)) : [])));
  }

  function renderSectors(c) {
    const limit = data.limits.sectorLimit;
    $('sector-bars').replaceChildren(...c.sectors.map(([name, w]) => el('li', { class: w > limit ? 'off-target' : null },
      el('span', { class: 'bar-label' }, name),
      el('span', { class: 'bar' },
        el('span', { class: 'bar-fill', style: `width:${Math.min(w, 1) * 100}%` }),
        el('span', { class: 'bar-limit', style: `left:${limit * 100}%` })),
      el('span', { class: 'bar-value' }, pct(w)))));
    $('sector-help').textContent = `세로선은 업종 한도(${pct(limit)})예요. 한 업종이 한도를 넘으면 빨갛게 보여요.`;
  }

  function renderHoldings(c) {
    $('holding-head').replaceChildren(el('tr', {},
      el('th', {}, '종목'), el('th', { class: 'num' }, '수량'),
      editing ? el('th', { class: 'num' }, '평균 매입가') : el('th', { class: 'num' }, '현재가'),
      el('th', { class: 'num' }, '평가금액'), el('th', { class: 'num' }, '비중'),
      el('th', { class: 'num' }, '목표'), editing ? el('th', {}) : el('th', { class: 'num' }, '수익률')));

    $('holding-rows').replaceChildren(...c.stocks.map((s) => {
      const d = draft.stocks.find((x) => x.code === s.code);
      const ret = s.avgPrice && s.close ? s.close / s.avgPrice - 1 : null;
      const name = el('td', {}, el('a', { href: '#', onclick: (e) => { e.preventDefault(); select(s.code); } }, s.name),
        ' ', el('span', { class: 'muted small' }, s.code),
        s.delistRules?.length ? el('span', { class: 'warn-text small' }, ' 상장폐지 위험') : null);
      if (!editing) {
        return el('tr', { class: s.code === selected ? 'selected' : null }, name,
          el('td', { class: 'num' }, `${s.quantity.toLocaleString('ko-KR')}주`),
          el('td', { class: 'num' }, s.close ? won(s.close) : '-'),
          el('td', { class: 'num' }, won(s.value)),
          el('td', { class: 'num' }, pct(s.weight, 1)),
          el('td', { class: 'num muted' }, s.targetWeight == null ? '-' : pct(s.targetWeight, 1)),
          el('td', { class: `num ${ret == null ? 'muted' : ret >= 0 ? 'up' : 'down'}` },
            ret == null ? '-' : `${ret >= 0 ? '+' : ''}${(ret * 100).toFixed(1)}%`));
      }
      const qty = el('input', { type: 'number', min: 0, step: 1, value: d.quantity, class: 'qty' });
      qty.addEventListener('input', () => { d.quantity = Math.max(0, Math.floor(Number(qty.value) || 0)); rerenderKeepFocus(qty); });
      const avg = el('input', { type: 'text', inputmode: 'numeric', value: d.avgPrice ? Math.round(d.avgPrice).toLocaleString('ko-KR') : '', placeholder: '모름', class: 'qty wide-input' });
      money(avg);
      avg.addEventListener('change', () => { d.avgPrice = digits(avg.value) || null; });
      return el('tr', { 'data-code': s.code }, name, el('td', { class: 'num' }, qty), el('td', { class: 'num' }, avg),
        el('td', { class: 'num' }, won(s.value)), el('td', { class: 'num' }, pct(s.weight, 1)),
        el('td', { class: 'num muted' }, s.targetWeight == null ? '-' : pct(s.targetWeight, 1)),
        el('td', { class: 'num' }, el('button', {
          type: 'button', class: 'small',
          onclick: () => { draft.stocks = draft.stocks.filter((x) => x.code !== s.code); render(); },
        }, '삭제')));
    }));
    if (!c.stocks.length) {
      $('holding-rows').replaceChildren(el('tr', {}, el('td', { colspan: 7, class: 'muted' }, '갖고 계신 주식이 없어요.')));
    }

    // 채권·현금
    const moneyCell = (id, value, onChange) => {
      if (!editing) {
        $(id).textContent = won(value);
        return;
      }
      const input = el('input', { type: 'text', inputmode: 'numeric', value: Math.round(value).toLocaleString('ko-KR'), class: 'wide-input' });
      money(input);
      input.addEventListener('input', () => { onChange(digits(input.value)); rerenderKeepFocus(input, id); });
      $(id).replaceChildren(input, ' 원');
    };
    moneyCell('bond-cell', draft.bondValue, (v) => { draft.bondValue = v; });
    moneyCell('cash-cell', draft.cash, (v) => { draft.cash = v; });

    const gone = data.targetOnly ?? [];
    $('target-only').textContent = gone.length
      ? `목표 비중에는 있지만 지금은 없는 종목: ${gone.map((g) => `${g.name}(${pct(g.targetWeight, 1)})`).join(', ')}`
      : '';
  }

  /** 입력 중에는 표를 통째로 다시 그리지 않고, 그래프·비중만 다시 그린다 (커서가 튀지 않게). */
  function rerenderKeepFocus() {
    const c = computed();
    $('total').textContent = won(c.total);
    renderAssets(c);
    renderChart(c);
    renderDetail(c);
    renderSectors(c);
    // 표의 금액·비중 칸만 갱신 (입력 중 순서가 바뀔 수 있어 종목코드로 찾는다)
    c.stocks.forEach((s) => {
      const r = $('holding-rows').querySelector(`tr[data-code="${s.code}"]`);
      if (!r) return;
      r.cells[3].textContent = won(s.value);
      r.cells[4].textContent = pct(s.weight, 1);
    });
  }

  // ------------------------------------------------------------ 수정 모드

  $('edit-btn').addEventListener('click', () => { editing = true; draft = fromServer(); render(); });
  $('cancel-btn').addEventListener('click', () => { editing = false; draft = fromServer(); $('edit-error').hidden = true; render(); });
  $('save-btn').addEventListener('click', save);

  let timer = null;
  $('add-search').addEventListener('input', (e) => {
    clearTimeout(timer);
    const q = e.target.value.trim();
    if (!q) { $('add-results').replaceChildren(); return; }
    timer = setTimeout(async () => {
      const list = await api('GET', `/api/stocks?q=${encodeURIComponent(q)}&limit=8`).catch(() => []);
      $('add-results').replaceChildren(...list.map((s) => el('li', {}, el('button', {
        type: 'button',
        onclick: () => {
          const found = draft.stocks.find((x) => x.code === s.code);
          if (found) found.quantity += 1;
          else draft.stocks.push({ code: s.code, name: s.name, sector: s.sector, quantity: 1, close: s.lastClose,
            avgPrice: null, targetWeight: null, delistRules: s.delistRules });
          $('add-search').value = '';
          $('add-results').replaceChildren();
          render();
        },
      }, `${s.name} `, el('span', { class: 'muted' }, `${s.code} · ${s.sector}`)))));
    }, 250);
  });

  // ------------------------------------------------------------ 추가 투자 (FR-PF-05)

  money($('invest-amount'));
  $('invest-btn').addEventListener('click', async () => {
    const amount = digits($('invest-amount').value);
    const err = $('invest-error');
    if (!amount || amount < min) {
      err.textContent = `투자 금액은 ${min.toLocaleString('ko-KR')}원 이상이어야 해요.`;
      err.hidden = false;
      return;
    }
    err.hidden = true;
    const btn = $('invest-btn');
    btn.disabled = true;
    btn.textContent = '계산하는 중…';
    try {
      const r = await api('POST', '/api/recommendations', { investAmount: amount });
      location.href = `/recommendations/${r.recommendationId}`;
    } catch (e) {
      err.textContent = e.message;
      err.hidden = false;
      btn.disabled = false;
      btn.textContent = '추천 받기';
    }
  });

  load();
})();
