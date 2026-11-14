/*
 * 온보딩·재진단 단계형 설문 (docs/02 §3.4, 03 §7.3 #7).
 * 단계 전환은 브라우저에서만 하고, 마지막에 한 번만 서버로 보낸다.
 *   온보딩: 투자 금액 → 성향 7문항 → 추가 문항 → 보유 종목 → 확인  →  POST /api/onboarding
 *   재진단:              성향 7문항 → 추가 문항 →              확인  →  PUT  /api/profile/survey
 */
(() => {
  const root = document.getElementById('wizard');
  const mode = root.dataset.mode;
  const $ = (id) => document.getElementById(id);
  const body = $('step-body');
  const errorBox = $('error');

  const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
  const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;

  const STEPS = mode === 'resurvey'
    ? [['core', '성향 진단'], ['extra', '추가 질문'], ['review', '확인']]
    : [['amount', '투자 금액'], ['core', '성향 진단'], ['extra', '추가 질문'], ['holdings', '보유 종목'], ['review', '확인']];

  const manwon = (n) => (n >= 1e8 ? (n / 1e8).toLocaleString('ko-KR') + '억' : (n / 1e4).toLocaleString('ko-KR') + '만') + ' 원';

  let survey = null;
  let sectors = [];
  let minInvest = 0;
  let step = 0;

  const prefill = window.__PREFILL__;
  const state = {
    investAmount: null,
    q: prefill ? [...prefill.q] : Array(7).fill(null),
    a2: prefill?.a2 ?? null,
    a3: prefill ? prefill.a3 : null,
    a4: prefill?.a4 ?? null,
    a5: prefill?.a5 ?? null,
    a6: prefill ? [...prefill.a6] : [],
    a7: prefill ? [...prefill.a7] : [],
    holdings: [],      // {code, name, quantity}
  };

  // ---------- 공통 그리기 ----------
  function el(tag, attrs = {}, ...children) {
    const e = document.createElement(tag);
    Object.entries(attrs).forEach(([k, v]) => {
      if (k === 'class') e.className = v;
      else if (k.startsWith('on')) e.addEventListener(k.slice(2), v);
      else if (v !== false && v != null) e.setAttribute(k, v === true ? '' : v);
    });
    children.flat().forEach((c) => e.append(c instanceof Node ? c : document.createTextNode(String(c))));
    return e;
  }

  /** 하나만 고르는 질문. value는 1부터. */
  function single(question, current, onPick, options = question.options) {
    return el('fieldset', { class: 'question' },
      el('legend', {}, question.text),
      el('div', { class: 'choices' }, options.map((label, i) =>
        el('button', {
          type: 'button',
          class: 'choice' + (current === i + 1 ? ' selected' : ''),
          'aria-pressed': current === i + 1 ? 'true' : 'false',
          onclick: () => { onPick(i + 1); render(); },
        }, label))));
  }

  function showError(msg) {
    errorBox.textContent = msg;
    errorBox.hidden = !msg;
  }

  // ---------- 단계별 화면 ----------
  const views = {
    amount() {
      const input = el('input', {
        type: 'number', min: minInvest, step: 1000000, inputmode: 'numeric',
        value: state.investAmount ?? '', placeholder: String(minInvest),
        oninput: (e) => { state.investAmount = e.target.value ? Number(e.target.value) : null; hint.textContent = state.investAmount ? manwon(state.investAmount) : ''; },
      });
      const hint = el('span', { class: 'muted' }, state.investAmount ? manwon(state.investAmount) : '');
      const quick = [20000000, 30000000, 50000000, 100000000].map((v) =>
        el('button', { type: 'button', class: 'chip', onclick: () => { state.investAmount = v; render(); } }, manwon(v)));
      return [
        el('h2', {}, '이번에 투자할 금액'),
        el('p', { class: 'muted' }, `이 돈을 주식·채권·현금으로 나눠 드려요. 최소 ${manwon(minInvest)}부터 시작할 수 있어요.`),
        el('label', { class: 'amount' }, input, hint),
        el('div', { class: 'chips' }, quick),
      ];
    },

    core() {
      return [
        el('h2', {}, '투자 성향 진단 (7문항)'),
        survey.questions.map((q, i) => single(q, state.q[i], (v) => { state.q[i] = v; })),
      ];
    },

    extra() {
      const [a2, a3, a4, a5, a6, a7] = survey.extras;
      const a6View = el('fieldset', { class: 'question' },
        el('legend', {}, a6.text),
        el('div', { class: 'choices' }, a6.keyedOptions.map((o) => {
          const on = state.a6.includes(o.key);
          return el('button', {
            type: 'button', class: 'choice' + (on ? ' selected' : ''), 'aria-pressed': on ? 'true' : 'false',
            onclick: () => {
              if (o.key === 'ANY') state.a6 = on ? [] : ['ANY'];             // '상관없음'은 혼자만
              else state.a6 = on ? state.a6.filter((k) => k !== o.key)
                                 : [...state.a6.filter((k) => k !== 'ANY'), o.key];
              render();
            },
          }, o.text);
        })));
      const a7View = el('details', { class: 'question', open: state.a7.length > 0 },
        el('summary', {}, `${a7.text}${state.a7.length ? ` · ${state.a7.length}개 선택` : ''}`),
        el('div', { class: 'choices small' }, sectors.map((s) => {
          const on = state.a7.includes(s);
          return el('button', {
            type: 'button', class: 'choice' + (on ? ' selected' : ''), 'aria-pressed': on ? 'true' : 'false',
            onclick: () => { state.a7 = on ? state.a7.filter((x) => x !== s) : [...state.a7, s]; render(); },
          }, s);
        })));
      return [
        el('h2', {}, '추가 질문'),
        single(a2, state.a2, (v) => { state.a2 = v; }),
        single(a3, state.a3 === null ? null : (state.a3 ? 1 : 2), (v) => { state.a3 = v === 1; }),
        single(a4, state.a4, (v) => { state.a4 = v; }),
        single(a5, state.a5, (v) => { state.a5 = v; }),
        a6View,
        a7View,
      ];
    },

    holdings() {
      const results = el('ul', { class: 'search-results' });
      let timer = null;
      const search = el('input', {
        type: 'search', placeholder: '종목명 또는 코드로 검색 (예: 삼성전자, 005930)',
        oninput: (e) => {
          clearTimeout(timer);
          const q = e.target.value.trim();
          if (!q) { results.replaceChildren(); return; }
          timer = setTimeout(async () => {
            const r = await fetch(`/api/stocks?q=${encodeURIComponent(q)}&limit=8`);
            const list = r.ok ? await r.json() : [];
            results.replaceChildren(...list.map((s) => el('li', {},
              el('button', {
                type: 'button',
                onclick: () => {
                  const found = state.holdings.find((h) => h.code === s.code);
                  if (found) found.quantity += 1;
                  else state.holdings.push({ code: s.code, name: s.name, quantity: 1 });
                  render();
                },
              }, `${s.name} `, el('span', { class: 'muted' }, `${s.code} · ${s.sector}`)))));
          }, 250);
        },
      });
      const rows = state.holdings.map((h, i) => el('tr', {},
        el('td', {}, h.name, ' ', el('span', { class: 'muted' }, h.code)),
        el('td', {}, el('input', {
          type: 'number', min: 1, step: 1, value: h.quantity, class: 'qty',
          oninput: (e) => { h.quantity = Number(e.target.value); },
        }), ' 주'),
        el('td', {}, el('button', { type: 'button', onclick: () => { state.holdings.splice(i, 1); render(); } }, '빼기'))));
      return [
        el('h2', {}, '이미 갖고 있는 KOSPI 종목'),
        el('p', { class: 'muted' }, '없으면 그냥 다음으로 넘어가세요. 몇 주 갖고 있는지만 알려 주시면 평가금액은 저희가 계산해요.'),
        search,
        results,
        state.holdings.length
          ? el('table', { class: 'table' }, el('thead', {}, el('tr', {}, el('th', {}, '종목'), el('th', {}, '수량'), el('th', {}, ''))), el('tbody', {}, rows))
          : el('p', { class: 'muted' }, '추가한 종목이 없어요.'),
      ];
    },

    review() {
      const [a2, a3, a4, a5, a6] = survey.extras;
      const label6 = state.a6.map((k) => a6.keyedOptions.find((o) => o.key === k)?.text).join(', ');
      const items = [
        ...(mode === 'onboarding' ? [['투자 금액', manwon(state.investAmount)]] : []),
        ...survey.questions.map((q, i) => [q.text, q.options[state.q[i] - 1]]),
        [a2.text, a2.options[state.a2 - 1]],
        [a3.text, state.a3 ? '있다' : '없다'],
        [a4.text, a4.options[state.a4 - 1]],
        [a5.text, a5.options[state.a5 - 1]],
        [a6.text, label6],
        ['제외할 업종', state.a7.length ? state.a7.join(', ') : '없음'],
        ...(mode === 'onboarding'
          ? [['보유 종목', state.holdings.length ? state.holdings.map((h) => `${h.name} ${h.quantity}주`).join(', ') : '없음']]
          : []),
      ];
      return [
        el('h2', {}, '이대로 제출할까요?'),
        el('dl', { class: 'review' }, items.flatMap(([k, v]) => [el('dt', {}, k), el('dd', {}, v ?? '-')])),
      ];
    },
  };

  // ---------- 단계 검사 ----------
  function check(name) {
    switch (name) {
      case 'amount':
        if (!state.investAmount || state.investAmount < minInvest) return `투자 금액은 ${manwon(minInvest)} 이상이어야 해요.`;
        if (!Number.isInteger(state.investAmount)) return '투자 금액은 원 단위 정수로 입력해 주세요.';
        return null;
      case 'core':
        return state.q.some((x) => x == null) ? '7문항에 모두 답해 주세요.' : null;
      case 'extra':
        if ([state.a2, state.a3, state.a4, state.a5].some((x) => x == null)) return '추가 질문에 모두 답해 주세요.';
        if (!state.a6.length) return '끌리는 투자 스타일을 하나 이상 골라 주세요. 없으면 "상관없음"을 골라 주세요.';
        return null;
      case 'holdings':
        return state.holdings.some((h) => !Number.isInteger(h.quantity) || h.quantity < 1) ? '수량은 1주 이상 정수로 입력해 주세요.' : null;
      default:
        return null;
    }
  }

  // ---------- 제출 ----------
  async function submit() {
    const answers = { q: state.q, a2: state.a2, a3: state.a3, a4: state.a4, a5: state.a5, a6: state.a6, a7: state.a7 };
    const req = mode === 'resurvey'
      ? { method: 'PUT', url: '/api/profile/survey', body: answers }
      : { method: 'POST', url: '/api/onboarding',
          body: { investAmount: state.investAmount, ...answers, holdings: state.holdings.map(({ code, quantity }) => ({ code, quantity })) } };
    $('next').disabled = true;
    try {
      const r = await fetch(req.url, {
        method: req.method,
        headers: { 'Content-Type': 'application/json', ...(csrfHeader ? { [csrfHeader]: csrfToken } : {}) },
        body: JSON.stringify(req.body),
      });
      if (r.ok) { location.href = '/profile'; return; }
      const err = (await r.json().catch(() => null))?.error;
      const detail = err?.detail?.problems?.join(' / ') || (err?.detail?.codes ? `종목코드: ${err.detail.codes.join(', ')}` : '');
      showError(`${err?.message ?? '제출하지 못했어요.'}${detail ? ' (' + detail + ')' : ''}`);
    } catch (e) {
      showError('서버에 연결할 수 없어요. 잠시 후 다시 시도해 주세요.');
    } finally {
      $('next').disabled = false;
    }
  }

  // ---------- 화면 갱신 ----------
  function render() {
    $('steps').replaceChildren(...STEPS.map(([, label], i) =>
      el('li', { class: i === step ? 'current' : i < step ? 'done' : '' }, label)));
    body.replaceChildren(...[views[STEPS[step][0]]()].flat(2));
    $('prev').hidden = step === 0;
    $('next').textContent = step === STEPS.length - 1 ? (mode === 'resurvey' ? '다시 진단하기' : '제출하기') : '다음';
  }

  $('prev').addEventListener('click', () => { showError(''); step = Math.max(0, step - 1); render(); });
  $('next').addEventListener('click', () => {
    const problem = check(STEPS[step][0]);
    if (problem) { showError(problem); return; }
    showError('');
    if (step === STEPS.length - 1) { submit(); return; }
    step += 1;
    render();
    window.scrollTo({ top: 0 });
  });

  fetch('/api/onboarding/survey')
    .then((r) => r.json())
    .then((data) => {
      survey = data.survey;
      sectors = data.sectors;
      minInvest = data.minInvestAmount;
      render();
    })
    .catch(() => showError('설문을 불러오지 못했어요. 새로고침해 주세요.'));
})();
