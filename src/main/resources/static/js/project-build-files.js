var ProjectBuildFiles = (function () {
  'use strict';
  const states = new WeakMap();
  const kinds = ['dockerfile', 'ci'];
  const meta = {
    dockerfile: {editor: 'dockerfile-editor', label: 'Dockerfile', endpoint: 'dockerfile'},
    ci: {editor: 'ci-editor', label: 'GitLab CI', endpoint: 'ci-file'}
  };
  const el = id => document.getElementById(id);
  const canEdit = s => s.page.dataset.canReviewMergeRequests === 'true';
  const current = s => el('project-data') === s.page && s.page.isConnected !== false;
  const notify = (message, kind) => showToast(message, kind || 'error');
  const context = () => ({ref: el('build-files-ref')?.value.trim() || '', componentId: el('build-files-component')?.value || ''});
  const sameContext = (left, right) => left.ref === right.ref && left.componentId === right.componentId;
  const targetBranch = () => el('proj-mr-target-branch')?.value.trim() || el('proj-branch')?.value.trim() || 'main';
  const endpoint = (s, suffix) => '/api/v1/gitlab/deploy-projects/' + encodeURIComponent(s.page.dataset.id) + '/' + suffix;
  function state() {
    const page = el('project-data');
    if (!page || !el('project-build-files')) return null;
    if (!states.has(page)) {
      const s = {page, files: {}, context: null, profile: null, profileSequence: 0, profileBusy: false};
      kinds.forEach(kind => {
        s.files[kind] = {kind, sequence: 0, status: 'idle', original: '', exists: false, snapshot: null, preview: null, submitted: false, busy: false};
        const editor = el(meta[kind].editor);
        editor?.addEventListener('input', () => { updateCode(editor); refresh(s, kind); });
        editor?.addEventListener('scroll', () => syncScroll(editor));
        editor?.addEventListener('keydown', event => keydown(event, editor));
        const previewCode = el(kind + '-preview-code');
        previewCode?.addEventListener('scroll', () => syncScroll(previewCode));
        updateCode(editor);
      });
      ['build-files-ref', 'build-files-component', 'build-source-type'].forEach(id => {
        el(id)?.addEventListener(id === 'build-files-ref' ? 'input' : 'change', () => {
          s.profileSequence++; s.profile = null; s.profileBusy = false;
          kinds.forEach(kind => { s.files[kind].preview = null; hidePreview(kind); refresh(s, kind); });
          profileStatus(id === 'build-source-type' ? '선택한 유형으로 초안을 미리 볼 수 있습니다. 저장소 파일은 바뀌지 않습니다.' : '조회 기준이 바뀌었습니다. 파일 불러오기를 눌러 주세요.');
        });
      });
      states.set(page, s);
    }
    return states.get(page);
  }
  function fileStatus(kind, text, level) {
    const node = el(kind + '-file-state');
    if (node) { node.textContent = text; node.className = 'build-file-state' + (level ? ' is-' + level : ''); }
    if (kind === 'ci' && el('ci-status')) el('ci-status').textContent = text;
  }
  function profileStatus(text, warning) {
    const node = el('build-detect-status');
    if (node) { node.hidden = false; node.textContent = text; node.className = 'notice-box' + (warning ? ' warning' : ''); }
  }
  function ready(s, f) {
    return current(s) && f.snapshot && sameContext(s.context, context()) && !f.busy && (f.status === 'ready' || f.status === 'missing');
  }
  function refresh(s, kind) {
    const f = s.files[kind], editor = el(meta[kind].editor);
    if (!editor || !current(s)) return;
    editor.readOnly = !canEdit(s) || !ready(s, f) || f.submitted;
    const changed = editor.value !== f.original;
    const save = el(kind === 'ci' ? 'ci-save' : 'dockerfile-save');
    if (save) { save.disabled = !canEdit(s) || !ready(s, f) || f.submitted || !changed || !editor.value.trim(); save.textContent = f.busy && f.saving ? 'MR 생성 중…' : '수정 사항 MR 올리기'; }
    const compare = el(kind + '-compare');
    if (compare) compare.disabled = !ready(s, f);
    const preview = el(kind + '-template');
    if (preview) preview.disabled = !canEdit(s) || !ready(s, f) || s.profileBusy;
    const mode = el(kind + '-editor-mode');
    if (mode) mode.textContent = f.submitted ? 'MR 제출 완료 · 대상 브랜치 유지' : editor.readOnly ? '읽기 전용' : changed ? '편집 중 · 저장소 미반영' : '편집 가능';
    const apply = el(kind + '-apply-preview');
    if (apply) apply.disabled = !canEdit(s) || !ready(s, f) || f.submitted || !f.preview;
    const reload = el('build-files-reload');
    if (reload) reload.disabled = kinds.some(key => s.files[key].busy);
  }
  async function request(url, options) {
    const response = await fetch(url, options || {cache: 'no-store'});
    const result = await response.json();
    if (response.ok === false || result.success !== true) throw new Error(result.message || '요청을 처리하지 못했습니다. 권한과 저장소 연결을 확인해 주세요.');
    return result.data || {};
  }
  function params(s, includeComponent) {
    const query = new URLSearchParams({ref: s.context.ref});
    if (includeComponent && s.context.componentId) query.set('componentId', s.context.componentId);
    return query;
  }
  function open(force) {
    const s = state();
    if (!s || kinds.some(kind => s.files[kind].busy)) return;
    if (!force && s.context && sameContext(s.context, context()) && kinds.every(kind => s.files[kind].snapshot)) return;
    const selected = context();
    if (!selected.ref) { notify('조회할 브랜치를 입력해 주세요.'); el('build-files-ref')?.focus(); return; }
    const before = kinds.map(kind => el(meta[kind].editor).value);
    const perform = () => {
      if (!current(s) || !sameContext(selected, context()) || kinds.some((kind, index) => s.files[kind].busy || el(meta[kind].editor).value !== before[index])) {
        notify('조회 기준이나 편집 내용이 바뀌어 다시 불러오지 않았습니다.'); return;
      }
      s.context = selected; s.profileSequence++; s.profile = null;
      kinds.forEach(kind => read(s, kind));
    };
    if (kinds.some((kind, index) => s.files[kind].snapshot && before[index] !== s.files[kind].original)) {
      openConfirm('작성 중인 내용을 다시 불러올까요?', '편집 중인 Dockerfile·CI 내용은 조회한 저장소 파일로 교체됩니다. MR로 제출하지 않은 내용은 사라집니다.', perform);
    } else perform();
  }
  async function read(s, kind) {
    const f = s.files[kind], editor = el(meta[kind].editor), before = editor.value, selected = {...s.context}, sequence = ++f.sequence;
    f.busy = true; f.status = 'loading'; f.snapshot = null; f.preview = null; f.submitted = false;
    hidePreview(kind); fileStatus(kind, '저장소 파일을 읽는 중입니다.'); refresh(s, kind);
    try {
      const data = await request(endpoint(s, meta[kind].endpoint) + '?' + params(s, kind === 'dockerfile'));
      if (!current(s) || sequence !== f.sequence || !sameContext(selected, context())) return;
      if (typeof data.exists !== 'boolean' || typeof data.content !== 'string' || !/^[a-f0-9]{40}$/i.test(data.baseCommitSha || '') || data.ref !== selected.ref || typeof data.filePath !== 'string' || !data.filePath || data.exists && !/^[a-f0-9]{64}$/i.test(data.contentSha256 || '')) {
        throw new Error('파일 조회 결과의 경로·기준 commit을 확인할 수 없습니다. 다시 불러와 주세요.');
      }
      if (editor.value !== before) { f.status = 'stale'; fileStatus(kind, '읽는 동안 작성 내용이 바뀌어 응답을 반영하지 않았습니다. 현재 내용은 유지됩니다.', 'error'); return; }
      f.snapshot = {filePath: data.filePath, ref: data.ref, baseCommitSha: data.baseCommitSha, originalSha256: data.contentSha256 || null, componentId: selected.componentId};
      f.exists = data.exists; f.original = data.exists ? data.content : ''; f.status = data.exists ? 'ready' : 'missing';
      editor.value = f.original;
      el(kind + '-file-path').textContent = data.filePath;
      el(kind + '-file-context').textContent = data.ref + ' · commit ' + data.baseCommitSha;
      fileStatus(kind, data.exists ? '저장소 원문을 불러왔습니다.' : canEdit(s) ? '조회 성공 · 이 경로에 파일이 없습니다. 유형을 선택해 생성 초안을 확인하거나 직접 작성하세요.' : '조회 성공 · 이 경로에 파일이 없습니다. 조회만 가능하며 파일 편집·MR 제안에는 MR 검토 권한이 필요합니다.', data.exists ? '' : 'missing');
      if (kind === 'ci' && typeof renderCiSuggestions === 'function') renderCiSuggestions(data.suggestions || {});
      updateCode(editor);
    } catch (error) {
      if (!current(s) || sequence !== f.sequence || !sameContext(selected, context())) return;
      f.status = 'error'; f.snapshot = null;
      fileStatus(kind, '조회 실패 · 파일 존재 여부 미확인: ' + error.message, 'error');
    } finally {
      if (current(s) && sequence === f.sequence) {
        f.busy = false;
        if (f.status === 'loading') { f.status = 'stale'; fileStatus(kind, '조회 기준이 변경되어 응답을 반영하지 않았습니다. 다시 불러와 주세요.', 'error'); }
        refresh(s, kind);
      }
    }
  }
  async function detect() {
    const s = state();
    if (!s || !s.context || !sameContext(s.context, context())) { notify('같은 기준의 Dockerfile·CI 파일을 먼저 불러와 주세요.'); return; }
    if (s.profileBusy) return;
    await profile(s, '');
  }
  async function profile(s, sourceType) {
    const sequence = ++s.profileSequence, selected = {...s.context};
    s.profileBusy = true; kinds.forEach(kind => refresh(s, kind)); profileStatus('소스 유형과 저장소 파일을 확인하는 중입니다. 편집 중인 내용은 유지됩니다.');
    try {
      const query = params(s, true); if (sourceType) query.set('sourceType', sourceType);
      const data = await request(endpoint(s, 'build-profile') + '?' + query);
      if (!current(s) || sequence !== s.profileSequence || !sameContext(selected, context())) return null;
      s.profile = data;
      const choice = el('build-source-type');
      if (data.selectionRequired !== true && !choice.value && data.sourceType && data.sourceType !== 'UNKNOWN') {
        choice.value = data.selectedSourceType || data.sourceType;
        if (window.DeploySelect?.sync) window.DeploySelect.sync(choice);
      }
      const evidence = Array.isArray(data.evidence) ? data.evidence : [];
      const warnings = Array.isArray(data.warnings) ? data.warnings : [];
      const heading = data.selectionRequired === true ? '유형 확인 필요 · 소스 유형을 직접 선택해 주세요.' : '소스 유형: ' + (data.selectedSourceType || data.sourceType || '미확인');
      profileStatus([heading, ...evidence, ...warnings, '감지 결과는 저장소 파일과 프로젝트 설정을 자동으로 변경하지 않습니다.'].join('\n'), data.selectionRequired === true || warnings.length > 0);
      return data;
    } catch (error) {
      if (current(s) && sequence === s.profileSequence) { s.profile = null; profileStatus('소스 조회 실패 · 유형 미확인: ' + error.message, true); }
      return null;
    } finally {
      if (current(s) && sequence === s.profileSequence) { s.profileBusy = false; kinds.forEach(kind => refresh(s, kind)); }
    }
  }
  async function preview(kind) {
    const s = state(), f = s?.files[kind];
    if (!s || !f || !canEdit(s)) { notify('파일 편집·MR 제안에는 이 프로젝트의 MR 검토 권한이 필요합니다.'); return; }
    if (!ready(s, f) || f.submitted) { notify('같은 기준의 저장소 파일을 먼저 불러와 주세요.'); return; }
    if (s.profileBusy) return;
    const sourceType = el('build-source-type').value;
    if (!sourceType || sourceType === 'UNKNOWN') { notify('생성할 소스 유형을 선택해 주세요.'); el('build-source-type').focus(); return; }
    const editor = el(meta[kind].editor), before = editor.value, snapshot = f.snapshot;
    const data = await profile(s, sourceType);
    if (!data || !current(s) || !canEdit(s) || !ready(s, f) || f.snapshot !== snapshot || editor.value !== before || el('build-source-type').value !== sourceType) return;
    if (data.selectionRequired === true) { notify('소스 유형이 확정되지 않아 초안을 적용할 수 없습니다.'); return; }
    if (data.baseCommitSha !== snapshot.baseCommitSha) { notify('감지 기준 commit이 파일 조회 후 바뀌었습니다. 파일을 다시 불러온 뒤 생성해 주세요.'); return; }
    const content = kind === 'ci' ? data.generatedCi : data.generatedDockerfile;
    if (typeof content !== 'string' || !content.trim()) { notify(data.generatedCiError || '이 유형의 생성 초안이 없습니다. 설정·경고를 확인하거나 직접 작성해 주세요.'); return; }
    f.preview = {content, before, snapshot, sourceType, profile: data};
    const panel = el(kind + '-preview'); panel.hidden = false;
    const code = el(kind + '-preview-code'); code.value = content; updateCode(code);
    el(kind + '-diff').textContent = diff(before, content, snapshot.filePath);
    el(kind + '-preview-title').textContent = '생성 초안 · ' + snapshot.filePath + ' · 아직 편집기에 반영하지 않음';
    refresh(s, kind); panel.scrollIntoView?.({block: 'nearest', behavior: 'smooth'});
  }
  function hidePreview(kind) { const node = el(kind + '-preview'); if (node) node.hidden = true; }
  function applyPreview(kind) {
    const s = state(), f = s?.files[kind], draft = f?.preview;
    if (!s || !f || !draft || !canEdit(s) || !ready(s, f) || f.submitted) return;
    const apply = () => {
      const editor = el(meta[kind].editor);
      if (!current(s) || !canEdit(s) || !ready(s, f) || f.snapshot !== draft.snapshot || f.preview !== draft || el('build-source-type').value !== draft.sourceType || editor.value !== draft.before) {
        notify('조회 기준이나 작성 내용이 바뀌었습니다. 현재 내용은 유지했으니 새 초안을 다시 확인해 주세요.'); return;
      }
      editor.value = draft.content; f.preview = null; hidePreview(kind); updateCode(editor); refresh(s, kind); editor.focus();
      notify('편집기에 초안을 넣었습니다. MR 승인·병합 전까지 대상 브랜치의 파일은 바뀌지 않습니다.', 'success');
    };
    openConfirm('생성 초안을 편집기에 반영할까요?', '현재 편집 중인 ' + draft.snapshot.filePath + ' 내용을 미리 본 초안으로 교체합니다. 저장소에 직접 반영하지 않습니다.', apply);
  }
  function compare(kind) {
    const s = state(), f = s?.files[kind];
    if (!s || !f || !ready(s, f)) return;
    const panel = el(kind + '-changes'); panel.hidden = false;
    el(kind + '-changes-code').textContent = diff(f.original, el(meta[kind].editor).value, f.snapshot.filePath);
  }
  function submit(kind) {
    const s = state(), f = s?.files[kind];
    if (!s || !f || !canEdit(s)) { notify('파일 편집·MR 제안에는 이 프로젝트의 MR 검토 권한이 필요합니다.'); return; }
    if (!ready(s, f) || f.submitted) { notify('저장소 파일을 다시 불러와 기준 commit을 확인해 주세요.'); return; }
    const editor = el(meta[kind].editor), content = editor.value, snapshot = f.snapshot;
    if (!content.trim()) { notify('MR로 제출할 파일 내용을 작성해 주세요.'); editor.focus(); return; }
    if (content === f.original) { notify('바뀐 내용이 없습니다.'); return; }
    if (snapshot.ref !== targetBranch()) {
      notify('MR 대상 브랜치와 조회 기준이 다릅니다. MR 대상 브랜치의 파일을 불러온 뒤 수정해 주세요.'); return;
    }
    compare(kind);
    openConfirm('파일 수정 MR을 만들까요?', snapshot.filePath + ' 변경을 ' + snapshot.ref + ' 대상의 새 MR로 제안합니다. 기본 브랜치에 바로 쓰지 않으며 기존 승인·병합 절차를 따릅니다.', async () => {
      if (!current(s) || !canEdit(s) || !ready(s, f) || f.snapshot !== snapshot || editor.value !== content || f.submitted || targetBranch() !== snapshot.ref) { notify('조회 기준이나 편집 내용이 바뀌어 제출하지 않았습니다. 다시 확인해 주세요.'); return; }
      const token = document.querySelector('meta[name="_csrf"]')?.content, header = document.querySelector('meta[name="_csrf_header"]')?.content;
      if (!token || !header) { notify('인증 정보를 확인할 수 없습니다. 화면을 새로 열어 주세요.'); return; }
      f.busy = true; f.saving = true; refresh(s, kind);
      const body = {content, targetBranch: snapshot.ref, baseCommitSha: snapshot.baseCommitSha, originalSha256: snapshot.originalSha256};
      if (kind === 'dockerfile') { body.filePath = snapshot.filePath; if (snapshot.componentId) body.componentId = Number(snapshot.componentId); }
      try {
        const result = await request(endpoint(s, meta[kind].endpoint), {method: 'POST', headers: {'Content-Type': 'application/json', [header]: token}, body: JSON.stringify(body)});
        if (!current(s)) return;
        f.submitted = true;
        const iid = result.mergeRequest?.iid;
        fileStatus(kind, 'MR' + (iid ? ' !' + iid : '') + ' 생성 완료 · 승인·병합 전 대상 브랜치의 원문은 유지됩니다.');
        notify('파일 수정 MR을 만들었습니다. MR 검토 화면에서 승인·병합해 주세요.', 'success');
        if (typeof mergeRequestsLoaded !== 'undefined') mergeRequestsLoaded = false;
        if (typeof loadMergeRequests === 'function') loadMergeRequests(true);
      } catch (error) { if (current(s)) { fileStatus(kind, 'MR 생성 실패 · 편집 내용 유지: ' + error.message, 'error'); notify(error.message); } }
      finally { if (current(s)) { f.busy = false; f.saving = false; refresh(s, kind); } }
    });
  }
  function indent(value, start, end, outdent) {
    if (!outdent && start === end) return {value: value.slice(0, start) + '  ' + value.slice(end), start: start + 2, end: end + 2};
    const begin = start === 0 ? 0 : value.lastIndexOf('\n', start - 1) + 1;
    const selectedEnd = end > start && value[end - 1] === '\n' ? end - 1 : end;
    const nextBreak = value.indexOf('\n', selectedEnd);
    const finish = nextBreak < 0 ? value.length : nextBreak;
    const rows = value.slice(begin, finish).split('\n');
    let firstChange = 0, totalChange = 0;
    const changed = rows.map((row, index) => {
      const count = outdent ? row.startsWith('\t') ? 1 : (row.match(/^ {1,2}/)?.[0].length || 0) : 0;
      const delta = outdent ? -count : 2;
      if (index === 0) firstChange = delta;
      totalChange += delta;
      return outdent ? row.slice(count) : '  ' + row;
    }).join('\n');
    return {value: value.slice(0, begin) + changed + value.slice(finish), start: Math.max(begin, start + firstChange), end: Math.max(begin, end + totalChange)};
  }
  function keydown(event, editor) {
    if (event.key === 'Escape') { editor._buildAllowTabExit = true; return; }
    if (event.key !== 'Tab' || event.ctrlKey || event.metaKey || event.altKey || editor.readOnly || editor.disabled) return;
    if (editor._buildAllowTabExit) { editor._buildAllowTabExit = false; return; }
    event.preventDefault();
    const change = indent(editor.value, editor.selectionStart, editor.selectionEnd, event.shiftKey);
    editor.value = change.value; editor.setSelectionRange(change.start, change.end);
    editor.dispatchEvent(new Event('input', {bubbles: true}));
  }
  function updateCode(editor) {
    if (!editor) return;
    const frame = editor.closest?.('.build-code-frame'), gutter = frame?.querySelector('.build-code-lines');
    if (gutter) gutter.textContent = Array.from({length: editor.value.split('\n').length}, (_, index) => index + 1).join('\n');
    syncScroll(editor);
  }
  function syncScroll(editor) {
    const gutter = editor?.closest?.('.build-code-frame')?.querySelector('.build-code-lines');
    if (gutter) gutter.scrollTop = editor.scrollTop;
  }
  function diff(before, after, path) {
    if (before === after) return '변경 사항이 없습니다.';
    const left = before.split('\n'), right = after.split('\n');
    let head = 0, tail = 0;
    while (head < left.length && head < right.length && left[head] === right[head]) head++;
    while (tail < left.length - head && tail < right.length - head && left[left.length - 1 - tail] === right[right.length - 1 - tail]) tail++;
    const rows = ['--- 조회 원본 / ' + path, '+++ 변경안 / ' + path];
    left.slice(Math.max(0, head - 3), head).forEach(line => rows.push('  ' + line));
    left.slice(head, left.length - tail).forEach(line => rows.push('- ' + line));
    right.slice(head, right.length - tail).forEach(line => rows.push('+ ' + line));
    right.slice(right.length - tail, right.length - tail + Math.min(3, tail)).forEach(line => rows.push('  ' + line));
    if (rows.length > 600) return rows.slice(0, 600).join('\n') + '\n… 비교 표시는 600줄까지입니다. 편집기·생성 초안에서 전체 내용을 확인하세요.';
    return rows.join('\n');
  }
  return {open, detect, preview, applyPreview, compare, submit, indent, keydown, diff};
})();
