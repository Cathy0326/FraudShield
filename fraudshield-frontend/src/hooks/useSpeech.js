import { useState, useRef, useCallback, useEffect } from 'react';

/**
 * 浏览器原生语音能力封装 —— 全部走Web Speech API：零后端、零密钥、离线可用。
 * 不支持的浏览器（如桌面Firefox的STT）优雅降级：supported=false，调用方隐藏按钮，
 * 打字/阅读路径完全不受影响。
 * Thin wrappers over the browser-native Web Speech API — no backend, no API keys.
 * Where a browser lacks a capability (e.g. desktop Firefox has no STT), the hook
 * reports supported=false and callers simply hide the affordance; the typed / visual
 * path is untouched.
 */

const SpeechRecognitionImpl =
  typeof window !== 'undefined' &&
  (window.SpeechRecognition || window.webkitSpeechRecognition);

/**
 * 听写：按住说话，最终结果通过onResult回调追加，中间结果实时显示为"幽灵文本"。
 * Dictation: push-to-talk. Final phrases are handed to onResult; interim words stream
 * back live as ghost text so the user sees it working.
 */
export function useDictation({ onResult } = {}) {
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState('');
  const recRef = useRef(null);
  // onResult存进ref —— 避免它每次渲染变化就重建recognition实例
  // Keep onResult in a ref so a new closure each render doesn't rebuild the recognizer
  const onResultRef = useRef(onResult);
  useEffect(() => { onResultRef.current = onResult; }, [onResult]);

  const stop = useCallback(() => {
    recRef.current?.stop();
  }, []);

  const start = useCallback(() => {
    if (!SpeechRecognitionImpl) return;
    const rec = new SpeechRecognitionImpl();
    rec.lang = 'en-US';
    rec.interimResults = true;
    rec.continuous = true;
    rec.onresult = (e) => {
      let interimStr = '';
      let finalStr = '';
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const chunk = e.results[i][0].transcript;
        if (e.results[i].isFinal) finalStr += chunk;
        else interimStr += chunk;
      }
      setInterim(interimStr);
      if (finalStr.trim()) {
        onResultRef.current?.(finalStr.trim());
      }
    };
    const done = () => { setListening(false); setInterim(''); };
    rec.onend = done;
    rec.onerror = done;
    recRef.current = rec;
    rec.start();
    setListening(true);
  }, []);

  const toggle = useCallback(() => {
    if (listening) stop(); else start();
  }, [listening, start, stop]);

  // 卸载时确保麦克风释放 / release the mic if the component unmounts mid-dictation
  useEffect(() => () => recRef.current?.stop(), []);

  return { supported: !!SpeechRecognitionImpl, listening, interim, start, stop, toggle };
}

/**
 * 朗读：把一段文本读出来，可随时打断。切换页面/组件卸载时自动停止。
 * Speech: read a string aloud, interruptible. Cancels on unmount so audio never
 * outlives the view that started it.
 */
export function useTts() {
  const synth = typeof window !== 'undefined' ? window.speechSynthesis : null;
  const [speaking, setSpeaking] = useState(false);

  const stop = useCallback(() => {
    synth?.cancel();
    setSpeaking(false);
  }, [synth]);

  const speak = useCallback((text) => {
    if (!synth || !text) return;
    synth.cancel();                       // 先打断在读的，避免叠音 / interrupt any prior utterance
    const u = new SpeechSynthesisUtterance(text);
    u.rate = 1.03;                        // 略快，像简报而非念课文 / brisk, briefing-paced
    u.onend = () => setSpeaking(false);
    u.onerror = () => setSpeaking(false);
    setSpeaking(true);
    synth.speak(u);
  }, [synth]);

  useEffect(() => () => synth?.cancel(), [synth]);

  return { supported: !!synth, speaking, speak, stop };
}
