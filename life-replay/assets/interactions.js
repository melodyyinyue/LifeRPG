(function () {
  'use strict';
  const el = id => document.getElementById(id);
  const show = (id, visible) => el(id).classList.toggle('hidden', !visible);
  const text = (id, value) => { el(id).textContent = value; };
  if (window.mermaid) {
    const css = getComputedStyle(document.documentElement);
    window.mermaid.initialize({ startOnLoad: true, theme: 'base', securityLevel: 'strict', themeVariables: {
      primaryColor: css.getPropertyValue('--soft').trim(), primaryTextColor: css.getPropertyValue('--ink').trim(),
      primaryBorderColor: css.getPropertyValue('--accent').trim(), lineColor: css.getPropertyValue('--muted').trim(),
      secondaryColor: css.getPropertyValue('--bg2').trim(), tertiaryColor: css.getPropertyValue('--bg').trim(),
      fontFamily: css.getPropertyValue('--font').trim()
    }});
  }
  el('print').onclick = () => window.print();
  el('seed-example').onclick = () => {
    el('seed').value = '近未来城市的一名记者，发现朋友参与了隐瞒事故真相。希望查清事实，也不想连累无辜的人。';
  };
  el('seed-next').onclick = () => {
    const hasSeed = Boolean(el('seed').value.trim());
    show('questions', hasSeed);
    text('create-status', hasSeed ? '示意：背景已收到，请补充主角目标。此处问题固定，未运行 AI。' : '还没有故事背景，请先输入文字。');
  };
  el('seed-confirm').onclick = () => {
    if (!el('seed').value.trim()) { text('create-status', '故事背景为空，请补充后确认。'); return; }
    const custom = el('motive-custom').value.trim();
    if (el('motive').value === '自定义' && !custom) { text('create-status', '选择自定义时，请填写目标。'); return; }
    text('create-status', '设定卡示意｜背景：' + el('seed').value.trim() + '｜目标：' + (el('motive').value === '自定义' ? custom : el('motive').value) + (custom && el('motive').value !== '自定义' ? '｜补充：' + custom : '') + '。下方游戏是独立固定样例，不由这份背景生成。');
  };
  let nodes, records, current, nextId, failNext, pending, exploring, snapshot, correction, reportMade, stale;
  const stages = [
    { title: '匿名来信', body: '一封来信称事故报告被修改，落款却指向你的朋友。你还没有核实附件真实性。', choices: ['先核验证据来源', '直接找朋友询问'] },
    { title: '证人请求', body: '证人愿意提供原始记录，但担心身份曝光。调查窗口正在缩小。', choices: ['先安排匿名渠道', '先拿到记录再处理保护'] },
    { title: '公开前夜', body: '证据指向一处关键矛盾。编辑要求立即发布，朋友希望你再核查一次。', choices: ['先独立复核再公开', '标注不确定性并公开'] }
  ];
  function reset() {
    nodes = [{ id: 0, parent: null, stage: 0, incoming: null, choice: null }];
    records = []; current = 0; nextId = 1; failNext = false; pending = null; exploring = false;
    snapshot = null; correction = ''; reportMade = false; stale = false;
    el('reason').value = ''; el('correction').value = ''; el('all-branches').checked = false;
    text('review-output', ''); show('review-confirm', false); show('review-correct', false);
    text('play-status', '示意已就绪。可先做三轮选择，也可立即进入复盘查看空状态。');
    render();
  }
  function pathIds(id) {
    const ids = new Set();
    while (id !== null) { ids.add(id); id = nodes.find(n => n.id === id).parent; }
    return ids;
  }
  function markStale() {
    snapshot = null;
    show('review-confirm', false);
    if (reportMade && !stale) {
      const notice = document.createElement('p'); notice.className = 'status';
      notice.textContent = '当前路径或记录已变化：以下是旧快照，请重新进入复盘更新。';
      el('review-output').prepend(notice); stale = true;
    }
  }
  function render() {
    const node = nodes.find(n => n.id === current);
    const stage = stages[node.stage];
    text('scene-title', 'N' + node.id + ' · ' + (stage ? stage.title : '结局：公开之后'));
    let body = stage ? stage.body : '报道引发了新的调查。你已走完这段示意故事，可回退探索，也可以主动复盘。';
    if (node.incoming) body = '上一选择：' + node.incoming.label + '。示意后果：' + node.incoming.outcome + ' ' + body;
    text('scene-text', body);
    el('choices').replaceChildren();
    if (stage) stage.choices.forEach((label, index) => {
      const button = document.createElement('button'); button.textContent = label;
      button.disabled = Boolean(pending); button.onclick = () => choose(index); el('choices').append(button);
    });
    el('reason').disabled = !stage || Boolean(pending);
    el('play-fail').disabled = !stage || Boolean(pending);
    show('play-retry', Boolean(pending));
    el('branch-list').replaceChildren();
    nodes.forEach(n => {
      const button = document.createElement('button');
      button.className = 'node' + (n.id === current ? ' active' : '');
      button.id = 'node-' + n.id;
      button.textContent = 'N' + n.id + (n.parent !== null ? ' ← N' + n.parent : ' · 开场') + (n.id === current ? ' · 当前' : ' · 回到此处');
      button.setAttribute('aria-pressed', String(n.id === current));
      button.onclick = () => {
        if (current === n.id) return;
        current = n.id; pending = null; failNext = false; exploring = true; el('reason').value = '';
        markStale(); text('play-status', '已回到 N' + n.id + '，原分支保留。后续新选择标记为探索。'); render();
      };
      el('branch-list').append(button);
    });
    text('branch-status', '已探索 ' + nodes.length + ' 个节点，保存 ' + records.length + ' 条有效选择。节点列表是画布导航的简化示意。');
  }
  function choose(index, retryData) {
    const node = nodes.find(n => n.id === current);
    const prior = nodes.find(n => n.parent === current && n.choice === index);
    if (prior) {
      current = prior.id; el('reason').value = ''; pending = null; failNext = false;
      markStale(); text('play-status', '已载入原有结果；没有重复生成，也没有新增分析样本。'); render(); return;
    }
    const data = retryData || { source: current, index, reason: el('reason').value.trim() };
    if (failNext) {
      failNext = false; pending = data;
      text('play-status', '模拟生成失败：选择尚未确认为有效记录。可重试，或回到其他节点。'); render(); return;
    }
    const label = stages[node.stage].choices[index];
    const outcomes = [
      ['你找到了附件的来源线索，但联系证人的窗口更短了。', '朋友给出一条证人线索，但已知道你在调查。'],
      ['证人愿意继续合作，传递记录的过程更谨慎。', '你更早拿到记录，证人仍担心身份暴露。'],
      ['复核补上了证据缺口，发布时机有所延后。', '报道更早发布，其中尚未确认的部分被明确列出。']
    ];
    const record = { id: 'R' + (records.length + 1), source: current, dest: nextId, label, reason: data.reason, exploration: exploring, outcome: outcomes[node.stage][index] };
    records.push(record);
    nodes.push({ id: nextId, parent: current, stage: node.stage + 1, incoming: record, choice: index });
    current = nextId++; pending = null; el('reason').value = '';
    markStale(); text('play-status', record.id + ' 已保存（页面内示意）：' + (record.exploration ? '探索选择' : '首次选择') + '。'); render();
  }
  el('play-fail').onclick = () => { failNext = true; text('play-status', '下一次未探索选项将模拟生成失败；失败不会新增记录。'); };
  el('play-retry').onclick = () => { if (pending) { const data = pending; pending = null; choose(data.index, data); } };
  el('play-reset').onclick = reset;
  el('all-branches').onchange = () => { snapshot = null; show('review-confirm', false); text('review-output', '范围已改变，请重新进入复盘确认。'); show('review-correct', false); reportMade = false; };
  el('review-open').onclick = () => {
    const all = el('all-branches').checked;
    const ids = pathIds(current);
    snapshot = { records: records.filter(r => all || ids.has(r.dest)).map(r => ({ ...r })), at: current, all };
    text('review-scope', '范围：' + (all ? '本故事所有已探索分支' : '当前路径截至 N' + current) + '；有效选择 ' + snapshot.records.length + ' 条。');
    show('review-confirm', true);
  };
  function paragraph(content) { const p = document.createElement('p'); p.textContent = content; el('review-output').append(p); }
  el('review-generate').onclick = () => {
    if (!snapshot) return;
    const selected = snapshot.records;
    el('review-output').replaceChildren();
    paragraph('复盘快照 · 截至 N' + snapshot.at + ' · ' + (snapshot.all ? '全部已探索分支' : '当前路径'));
    if (!selected.length) paragraph('还没有可复盘的选择。先进入故事做出选择；系统不会从空白记录推测你是什么样的人。');
    selected.forEach(r => {
      const p = document.createElement('p');
      const link = document.createElement('a'); link.href = '#node-' + r.source; link.textContent = r.id + ' / N' + r.source;
      p.append(link, document.createTextNode('：' + r.label + '（' + (r.exploration ? '探索' : '首次') + '）' + (r.reason ? '；当时理由：“' + r.reason + '”' : '；未提供理由，不能推断动机。')));
      el('review-output').append(p);
    });
    const independent = new Set(selected.filter(r => !r.exploration).map(r => r.source)).size;
    if (selected.length && independent < 3) paragraph('当前首次决策证据仍少，仅回顾事实。探索记录可作对照，但不能当成独立的人格样本。');
    if (independent >= 3) {
      paragraph('观察示意：这些选择涉及信息确认、他人安全与行动时机的取舍。具体依据见上方记录；选项本身不足以说明动机。');
      paragraph('待确认的问题：你主要担心信息不可靠、他人受损，还是只在扮演这个角色？这里不输出固定人格结论。');
    }
    if (correction) paragraph('玩家修正：“' + correction + '”。本规则示意不自动理解修正；暂不作个人倾向推断，只保留事实回顾。');
    show('review-confirm', false); show('review-correct', selected.length > 0); reportMade = true; stale = false;
  };
  el('correction-save').onclick = () => {
    const value = el('correction').value.trim();
    if (!value) { paragraph('请先填写希望修正的内容。'); return; }
    correction = value;
    paragraph('已在本页保存修正：“' + value + '”。旧解释不再作为当前结论，请重新进入复盘更新。');
    stale = true; snapshot = null; show('review-confirm', false);
  };
  el('review-close').onclick = () => { show('review-correct', false); show('review-confirm', false); el('demo-play').scrollIntoView({ behavior: 'smooth', block: 'start' }); };
  reset();
})();
