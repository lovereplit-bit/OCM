(function(){
const O=window.OCM,h=O.h,S=O.S,api=O.api,bus=O.bus;

O.CATALOG_MODELS = [
  {
    id: 'smollm2:1.7b',
    name: 'SmolLM2 1.7B Instruct',
    file: 'SmolLM2-1.7B-Instruct-Q4_K_M.gguf',
    url: 'https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf',
    size: '1.06 ГБ',
    quant: 'Q4_K_M',
    context: 4096,
    desc: 'Сверхлегкая и быстрая модель для базового чата и команд. Идеальна для слабых телефонов с 4-6 ГБ ОЗУ.',
    tags: ['GGUF Q4_K_M', '~1.06 ГБ', 'Офлайн', 'Быстрая']
  },
  {
    id: 'qwen2.5:1.5b',
    name: 'Qwen 2.5 1.5B Instruct',
    file: 'qwen2.5-1.5b-instruct-q4_k_m.gguf',
    url: 'https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf',
    size: '1.12 ГБ',
    quant: 'Q4_K_M',
    context: 4096,
    desc: 'Отличное качество русского и английского языка, следование инструкциям, логика и простой код от Alibaba.',
    tags: ['GGUF Q4_K_M', '~1.12 ГБ', 'Офлайн', 'Русский язык']
  },
  {
    id: 'llama3.2:3b',
    name: 'Llama 3.2 3B Instruct',
    file: 'Llama-3.2-3B-Instruct-Q4_K_M.gguf',
    url: 'https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf',
    size: '2.02 ГБ',
    quant: 'Q4_K_M',
    context: 8192,
    desc: 'Флагманская компактная модель от Meta. Высокое качество рассуждений, диалога и работы с текстом.',
    tags: ['GGUF Q4_K_M', '~2.02 ГБ', 'Офлайн', 'Meta']
  },
  {
    id: 'deepseek-r1:1.5b',
    name: 'DeepSeek-R1 Distill 1.5B',
    file: 'DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf',
    url: 'https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf',
    size: '1.12 ГБ',
    quant: 'Q4_K_M',
    context: 4096,
    desc: 'Рассуждающая модель (Reasoning) офлайн. Сильна в пошаговой логике, алгоритмах и математике без интернета.',
    tags: ['GGUF Q4_K_M', '~1.12 ГБ', 'Офлайн', 'Reasoning']
  },
  {
    id: 'gemma2:2b',
    name: 'Gemma 2 2B Instruct',
    file: 'gemma-2-2b-it-Q4_K_M.gguf',
    url: 'https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf',
    size: '1.63 ГБ',
    quant: 'Q4_K_M',
    context: 4096,
    desc: 'Продвинутая модель от Google DeepMind. Отличная связная речь, фактологическая точность и структурирование.',
    tags: ['GGUF Q4_K_M', '~1.63 ГБ', 'Офлайн', 'Google']
  },
  {
    id: 'phi3.5:3.8b',
    name: 'Phi-3.5 Mini Instruct',
    file: 'Phi-3.5-mini-instruct-Q4_K_M.gguf',
    url: 'https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf',
    size: '2.39 ГБ',
    quant: 'Q4_K_M',
    context: 8192,
    desc: 'Мощная модель для программирования и логики от Microsoft с поддержкой расширенного контекста.',
    tags: ['GGUF Q4_K_M', '~2.39 ГБ', 'Офлайн', 'Код и логика']
  }
];

// Active download tracking state
const dlState = {};
let redrawLocalModels = null;

O.on('model_download', d => {
  if (!d || !d.id) return;
  dlState[d.id] = d;
  if (d.status === 'completed') {
    O.syncLocalModelsConfig().then(() => {
      O.toast('Модель готова к работе офлайн!', 3000);
      if (redrawLocalModels) redrawLocalModels();
    });
  } else if (d.status === 'error') {
    O.toast(d.error || 'Ошибка загрузки модели', 4000);
    if (redrawLocalModels) redrawLocalModels();
  } else if (redrawLocalModels) {
    redrawLocalModels();
  }
});

// Sync downloaded models into opencode.json provider configuration
O.syncLocalModelsConfig = async () => {
  try {
    const list = O.native.listLocalModels() || [];
    const t = O.native.cfgRead() || '';
    let obj = {};
    try { if (t.trim()) obj = O.parseJsonc(t); } catch (_) {}
    obj.provider = obj.provider || {};

    const baseUrl = O.store.get('localEngineUrl', 'http://127.0.0.1:11434/v1');
    const pid = 'ollama';

    const modelsMap = {};
    list.forEach(f => {
      const fn = f.fileName;
      const cat = O.CATALOG_MODELS.find(m => m.file.toLowerCase() === fn.toLowerCase());
      const mid = cat ? cat.id : fn.replace(/\.gguf$/i, '').toLowerCase();
      const mname = cat ? cat.name : fn.replace(/\.gguf$/i, '');
      const ctx = cat ? cat.context : 4096;
      modelsMap[mid] = {
        name: mname + ' (Офлайн GGUF)',
        limit: { context: ctx },
        free: true,
        cost: { input: 0, output: 0 }
      };
    });

    if (Object.keys(modelsMap).length > 0) {
      obj.provider[pid] = {
        name: 'Ollama / Локально (Офлайн)',
        npm: '@ai-sdk/openai',
        options: {
          baseURL: baseUrl,
          apiKey: 'ollama'
        },
        models: modelsMap
      };
    }

    const w = O.native.cfgWrite(JSON.stringify(obj, null, 2));
    if (w && w.ok) {
      try { await api('POST', '/instance/dispose', null); } catch (_) {}
      await O.loadMeta();
      bus.emit('meta');
    }
    return true;
  } catch (e) {
    console.error('syncLocalModelsConfig error:', e);
    return false;
  }
};

O.activateLocalModel = async (mid, mname) => {
  await O.syncLocalModelsConfig();
  O.setModel('ollama', mid);
  O.toast(O.tr('Activated offline model: ', 'Активирована офлайн-модель: ') + mname, 2500);
};

// UI: Local Models Management Sheet
O.openLocalModels = (parentSheet) => {
  const body = h('div');
  const s = O.sheet(O.tr('Offline AI Models (GGUF)', 'Локальные ИИ-модели (Офлайн)'), body, { full: true, hud: true });

  const draw = () => {
    body.textContent = '';
    const downloaded = O.native.listLocalModels() || [];
    const downMap = new Map();
    downloaded.forEach(f => downMap.set(f.fileName.toLowerCase(), f));

    // 1. Engine & Endpoint Status
    const curUrl = O.store.get('localEngineUrl', 'http://127.0.0.1:11434/v1');
    const statusDot = h('span', { class: 'dot' });
    const statusText = h('span', { class: 'mut s' }, 'Проверка локального движка...');

    const pingEngine = async () => {
      try {
        const u = curUrl.replace(/\/+$/, '') + '/models';
        const res = await fetch(u, { method: 'GET', headers: { Accept: 'application/json' } });
        if (res.ok) {
          statusDot.className = 'dot ok';
          statusText.textContent = 'Локальный сервер активен (' + curUrl + ')';
        } else {
          statusDot.className = 'dot warn';
          statusText.textContent = 'Сервер отвечает с кодом HTTP ' + res.status;
        }
      } catch (_) {
        statusDot.className = 'dot';
        statusText.textContent = 'Локальный сервер не запущен (порт 11434)';
      }
    };
    pingEngine();

    const engineCard = h('div', { class: 'group-card', style: { padding: '14px 16px', marginBottom: '14px' } },
      h('div', { style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '8px' } },
        h('b', { style: { fontSize: '15px' } }, 'Локальный движок (Ollama / llama-server)'),
        h('div', { style: { display: 'flex', alignItems: 'center', gap: '6px' } }, statusDot, statusText)
      ),
      h('div', { class: 'mut s', style: { marginBottom: '10px', lineHeight: '1.4' } },
        'Модели хранятся в ', h('span', { class: 'mono' }, '/root/models/'), ' и работают полностью офлайн без отправки данных в сеть.'
      ),
      h('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap' } },
        h('button', { class: 'btn sm', onclick: () => {
          const nw = prompt('Введите Base URL локального сервера (OpenAI-совместимый):', curUrl);
          if (nw && nw.trim()) {
            O.store.set('localEngineUrl', nw.trim());
            O.syncLocalModelsConfig();
            draw();
          }
        } }, O.icon('edit'), 'Сменить URL: ' + curUrl),
        h('button', { class: 'btn sm', onclick: () => pingEngine() }, O.icon('refresh'), 'Проверить')
      )
    );
    body.append(engineCard);

    // 2. Catalog of Models
    body.append(h('div', { class: 'settings-section-title', style: { marginTop: '16px' } }, 'Каталог моделей GGUF'));
    const modelsListCard = h('div', { class: 'group-card' });

    O.CATALOG_MODELS.forEach(m => {
      const isDownloaded = downMap.has(m.file.toLowerCase());
      const downInfo = downMap.get(m.file.toLowerCase());
      const curDl = dlState[m.id];
      const isDl = curDl && curDl.status === 'downloading';
      const isActive = S.model && S.model.providerID === 'ollama' && S.model.modelID === m.id;

      const card = h('div', { class: 'model-card-item' });

      // Title & Badges
      const titleRow = h('div', { style: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '6px' } },
        h('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' } },
          h('b', { style: { fontSize: '16px', color: '#ffffff' } }, m.name),
          h('span', { class: 'badge-model badge-offline' }, 'GGUF'),
          h('span', { class: 'badge-model badge-free' }, m.size)
        ),
        isActive ? h('span', { class: 'badge-model badge-free' }, 'АКТИВНА') : null
      );

      // Description
      const descEl = h('div', { class: 'mut s', style: { lineHeight: '1.4' } }, m.desc);

      // Tags
      const tagsRow = h('div', { style: { display: 'flex', gap: '6px', flexWrap: 'wrap', margin: '4px 0' } },
        m.tags.map(t => h('span', { class: 'badge-tag' }, t))
      );

      // Action / Progress area
      let actionArea;
      if (isDl) {
        const pct = curDl.pct || 0;
        const spd = curDl.speedBps ? (curDl.speedBps / (1024 * 1024)).toFixed(1) + ' МБ/с' : '';
        const doneMb = curDl.doneBytes ? (curDl.doneBytes / (1024 * 1024)).toFixed(0) : '0';
        const totMb = curDl.totalBytes ? (curDl.totalBytes / (1024 * 1024)).toFixed(0) : '';

        const bar = h('div', { class: 'model-dl-bar', style: { width: pct + '%' } });
        const barWrap = h('div', { class: 'model-dl-bar-wrap' }, bar);

        actionArea = h('div', { style: { marginTop: '6px' } },
          h('div', { style: { display: 'flex', justifyContent: 'space-between', fontSize: '13px' } },
            h('span', null, 'Загрузка: ' + pct + '% (' + doneMb + ' / ' + totMb + ' МБ)'),
            h('span', { class: 'mut' }, spd)
          ),
          barWrap,
          h('button', { class: 'btn sm', style: { marginTop: '6px' }, onclick: () => {
            O.native.cancelModelDownload(m.id);
            delete dlState[m.id];
            draw();
          } }, O.icon('x'), 'Отменить')
        );
      } else if (isDownloaded) {
        actionArea = h('div', { style: { display: 'flex', gap: '8px', flexWrap: 'wrap', marginTop: '6px', alignItems: 'center' } },
          h('button', { class: 'btn pri sm', onclick: async () => {
            await O.activateLocalModel(m.id, m.name);
            s.close();
            if (parentSheet && parentSheet.close) parentSheet.close();
          } }, O.icon('check'), 'Выбрать для агента'),
          h('button', { class: 'btn sm', onclick: async () => {
            if (await O.confirm('Удалить модель?', 'Файл ' + m.file + ' будет удалён с устройства для освобождения места.', 'Удалить')) {
              O.native.deleteModel(m.file);
              O.syncLocalModelsConfig();
              O.toast('Модель удалена');
              draw();
            }
          } }, O.icon('trash'), 'Удалить (' + (downInfo ? (downInfo.size / (1024 * 1024 * 1024)).toFixed(2) + ' ГБ' : m.size) + ')')
        );
      } else {
        actionArea = h('div', { style: { marginTop: '6px' } },
          h('button', { class: 'btn pri sm wide', onclick: () => {
            const res = O.native.downloadModel(m.id, m.url, m.file);
            if (res && res.ok !== false) {
              dlState[m.id] = { pct: 0, status: 'downloading' };
              O.toast('Загрузка модели ' + m.name + ' началась...', 2000);
              draw();
            } else {
              O.toast('Не удалось начать загрузку', 3000);
            }
          } }, O.icon('down'), 'Скачать модель (' + m.size + ')')
        );
      }

      card.append(titleRow, descEl, tagsRow, actionArea);
      modelsListCard.append(card);
    });

    body.append(modelsListCard);

    // 3. Instructions & Import custom
    body.append(h('div', { class: 'settings-section-title', style: { marginTop: '20px' } }, 'Запуск и импорт в Ollama'));
    const infoCard = h('div', { class: 'group-card', style: { padding: '14px 16px' } },
      h('div', { class: 'mut s', style: { marginBottom: '8px' } },
        'Для запуска скачанных GGUF моделей через Ollama в терминале достаточно выполнить:'
      ),
      h('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '8px', background: 'var(--bg2)', padding: '8px 12px', borderRadius: '8px' } },
        h('span', { class: 'mono grow', style: { fontSize: '13px' } }, 'ollama serve'),
        h('button', { class: 'ibtn s', onclick: () => O.native.copy('ollama serve') }, O.icon('copy'))
      ),
      h('div', { style: { display: 'flex', alignItems: 'center', gap: '8px', background: 'var(--bg2)', padding: '8px 12px', borderRadius: '8px' } },
        h('span', { class: 'mono grow', style: { fontSize: '13px' } }, 'bash /root/models/import-to-ollama.sh'),
        h('button', { class: 'ibtn s', onclick: () => O.native.copy('bash /root/models/import-to-ollama.sh') }, O.icon('copy'))
      )
    );
    body.append(infoCard);
  };

  redrawLocalModels = draw;
  draw();
};

})();
