/*
 * 백테스트 결과 화면 (docs/03 §7.1, 01 FR-BT-10·11·13). 3초마다 상태를 읽고, 끝나면 실험별로 그린다.
 * 엔진 응답은 snake_case 그대로 온다 (docs/03 §6.6).
 */
(() => {
  const { api, pct } = App;
  const $ = (x) => document.getElementById(x);
  const id = $('bt').dataset.id;

  const EXP = {
    A: ['팩터별 성과', '팩터 하나만으로 고른 15종목(동일가중)과, 다섯 팩터를 섞은 멀티팩터(업종 안 순위·전체 순위)를 비교해요.'],
    B: ['비중 방식 비교', '같은 멀티팩터 15종목을 동일가중·변동성 역수가중·최소분산으로 나눴을 때를 비교해요.'],
    C: ['멀티팩터 vs 코스피', '멀티팩터 15종목(동일가중)과 코스피 지수를 비교해요.'],
    D: ['성향별 운용', '대표 성향 8개로 이 서비스의 추천을 그대로 따라 하고(매월 말 리밸런싱 알림 조건도 확인), '
      + '같은 주식·채권·현금 비율을 코스피 지수로 채운 "기준"과 비교해요.'],
  };
  const NAME = {
    VALUE: '가치', QUALITY: '수익성', MOMENTUM: '추세', STABILITY: '안정성', DIVIDEND: '배당',
    MULTI_NEUTRAL: '멀티팩터(업종 안 순위)', MULTI_GLOBAL: '멀티팩터(전체 순위)',
    MULTI_EW: '멀티·동일가중', MULTI_IV: '멀티·변동성 역수가중', MULTI_MV: '멀티·최소분산', KOSPI: '코스피',
    STABLE_REP: '안정추구형', NEUTRAL_REP: '위험중립형', AGGRESSIVE_REP: '공격추구형',
    LIMIT_NONE: '손실 한도 없음', LIMIT_20: '손실 한도 -20%', LIMIT_10: '손실 한도 -10%', LIMIT_05: '손실 한도 -5%',
    LIMIT_03: '손실 한도 -3%',
  };
  const label = (k) => (k.startsWith('BENCH_') ? `${NAME[k.slice(6)] ?? k.slice(6)} 기준` : NAME[k] ?? k);
  const LIMITS = {
    SURVIVORSHIP: '상장폐지된 종목은 자료에 없어, 실제보다 결과가 좋게 나올 수 있어요 (생존 편향).',
    NO_MANAGED_FILTER: '관리종목 여부는 과거 기록이 없어 백테스트에서는 거르지 않았어요.',
    SYNTHETIC_BOND: '채권은 국고채 3년 금리로 계산한 지수예요. 실제 채권 ETF와 조금 다를 수 있어요.',
  };
  const COLORS = ['#2f6fde', '#cf222e', '#4f9a5b', '#c9a227', '#8250df', '#e16f24', '#1b7c83', '#bf3989',
    '#59636e', '#0550ae', '#6e7781', '#953800', '#116329', '#a40e26', '#3b2300', '#8c959f'];

  let result = null;
  let nav = null;          // 주간 NAV (모든 전략)
  let charts = {};

  const el = (tag, attrs = {}, ...children) => {
    const e = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else if (v !== null && v !== undefined && v !== false) e.setAttribute(k, v === true ? '' : v);
    }
    e.append(...children.filter((c) => c !== null && c !== undefined));
    return e;
  };

  async function poll() {
    try {
      const st = await api('GET', `/api/backtests/${id}`);
      const p = Math.round((st.progress ?? 0) * 100);
      $('progress-bar').style.width = `${p}%`;
      if (st.status === 'DONE') {
        $('progress-card').hidden = true;
        result = st.result;
        nav = await api('GET', `/api/backtests/${id}/nav?freq=W`);
        render(st);
        return;
      }
      if (st.status === 'FAILED') {
        $('status-text').textContent = `계산에 실패했어요: ${st.error ?? ''}`;
        return;
      }
      $('status-text').textContent = st.status === 'QUEUED' ? '차례를 기다리는 중이에요…' : `계산 중이에요… ${p}%`;
      setTimeout(poll, 3000);
    } catch (e) {
      $('error').textContent = e.status === 404 ? '백테스트를 찾을 수 없어요.' : e.message;
      $('error').hidden = false;
    }
  }

  function render(st) {
    $('content').hidden = false;
    const d = result.rebalance_dates;
    const pr = st.params ?? {};
    $('meta').textContent = `${d[0]} ~ ${nav.dates[nav.dates.length - 1]} · 리밸런싱 ${d.length}회 · 거래 비용 `
      + `${((pr.trading_cost_rate ?? 0.003) * 100).toFixed(2)}% · 계산 ${(result.elapsed_ms / 1000).toFixed(1)}초`;
    const exps = Object.keys(result.experiments);
    $('tabs').replaceChildren(...exps.map((e, i) => el('button', {
      type: 'button', role: 'tab', class: 'tab', 'data-exp': e, onclick: () => show(e),
    }, `${e}. ${EXP[e][0]}`)));
    $('limits').replaceChildren(...result.limitations.map((k) => el('li', {}, LIMITS[k] ?? k)));
    $('csv-link').href = `/api/backtests/${id}/export.csv`;
    show(exps[0]);
  }

  function show(exp) {
    document.querySelectorAll('#tabs .tab').forEach((b) => b.classList.toggle('current', b.dataset.exp === exp));
    $('exp-help').textContent = EXP[exp][1];
    const keys = result.experiments[exp];
    drawLines('nav-chart', keys, 'nav', (v) => v.toFixed(1));
    drawLines('dd-chart', keys, 'drawdown', (v) => pct(v, 1));
    renderMetrics(exp, keys);
    renderYearly(keys);
    renderHoldingsPicker(keys);
  }

  function drawLines(canvas, keys, field, fmt) {
    if (!window.Chart) return;
    const datasets = keys.filter((k) => nav.strategies[k]).map((k, i) => ({
      label: label(k), data: nav.strategies[k][field], borderColor: COLORS[i % COLORS.length],
      borderWidth: k.startsWith('BENCH_') || k === 'KOSPI' ? 1 : 1.8,
      borderDash: k.startsWith('BENCH_') ? [4, 3] : [], pointRadius: 0, tension: 0,
    }));
    charts[canvas]?.destroy();
    charts[canvas] = new Chart($(canvas), {
      type: 'line',
      data: { labels: nav.dates, datasets },
      options: {
        maintainAspectRatio: false, animation: false, interaction: { mode: 'index', intersect: false },
        plugins: {
          legend: { display: canvas === 'nav-chart', position: 'bottom', labels: { boxWidth: 12 } },
          tooltip: { callbacks: { label: (c) => `${c.dataset.label} ${fmt(c.raw)}` } },
        },
        scales: { x: { ticks: { maxTicksLimit: 10 } }, y: { ticks: { callback: (v) => fmt(v) } } },
      },
    });
  }

  function renderMetrics(exp, keys) {
    const by = Object.fromEntries(result.strategies.map((s) => [s.strategy, s.metrics]));
    const cols = [['누적', 'cumulative_return', (v) => pct(v, 1)], ['연환산', 'cagr', (v) => pct(v, 1)],
      ['변동성', 'volatility', (v) => pct(v, 1)], ['샤프', 'sharpe', (v) => v.toFixed(2)],
      ['소르티노', 'sortino', (v) => v.toFixed(2)], ['최대 하락', 'max_drawdown', (v) => pct(v, 1)]];
    if (exp === 'D') {
      cols.push(['한도 아래 일수', 'limit_breach_days', (v) => `${v}일`], ['한도 아래 구간', 'limit_breach_episodes', (v) => `${v}번`]);
    }
    $('metrics').replaceChildren(
      el('thead', {}, el('tr', {}, el('th', {}, '전략'), ...cols.map(([h]) => el('th', { class: 'num' }, h)))),
      el('tbody', {}, ...keys.map((k) => el('tr', {}, el('td', {}, label(k)),
        ...cols.map(([, f, fmt]) => el('td', { class: 'num' }, by[k]?.[f] == null ? '-' : fmt(by[k][f])))))));
  }

  function renderYearly(keys) {
    const by = Object.fromEntries(result.strategies.map((s) => [s.strategy, Object.fromEntries(s.yearly.map((y) => [y.year, y.return]))]));
    const years = [...new Set(result.strategies.flatMap((s) => s.yearly.map((y) => y.year)))].sort();
    $('yearly').replaceChildren(
      el('thead', {}, el('tr', {}, el('th', {}, '전략'), ...years.map((y) => el('th', { class: 'num' }, String(y))))),
      el('tbody', {}, ...keys.map((k) => el('tr', {}, el('td', {}, label(k)),
        ...years.map((y) => {
          const v = by[k]?.[y];
          return el('td', { class: `num ${v == null ? '' : v >= 0 ? 'up' : 'down'}` }, v == null ? '-' : pct(v, 1));
        })))));
  }

  function renderHoldingsPicker(keys) {
    const sel = $('h-strategy');
    sel.replaceChildren(...keys.filter((k) => k !== 'KOSPI' && !k.startsWith('BENCH_')).map((k) => new Option(label(k), k)));
    $('h-date').replaceChildren(...[...result.rebalance_dates].reverse().map((d) => new Option(d, d)));
    sel.onchange = loadHoldings;
    $('h-date').onchange = loadHoldings;
    loadHoldings();
  }

  async function loadHoldings() {
    const strategy = $('h-strategy').value;
    if (!strategy) { $('h-rows').replaceChildren(); return; }
    const date = $('h-date').value;
    const h = await api('GET', `/api/backtests/${id}/holdings?strategy=${strategy}&date=${date}`);
    if (strategy !== $('h-strategy').value || date !== $('h-date').value) return;   // 그사이 다른 걸 골랐으면 버린다
    $('h-rows').replaceChildren(...h.holdings.map((x) => el('tr', {},
      el('td', {}, x.name ?? x.asset, x.asset.length === 6 ? el('span', { class: 'muted small' }, ` ${x.asset}`) : null),
      el('td', { class: 'num' }, pct(x.weight, 1)))));
  }

  poll();
})();
