import React, { useEffect, useRef, useState } from 'react';
import {
  AccessibilityInfo, ActivityIndicator, AppState, Image, KeyboardAvoidingView, Platform,
  Pressable, ScrollView, StyleSheet, Switch, Text, TextInput, useWindowDimensions, View,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import { StatusBar } from 'expo-status-bar';
import Feather from '@expo/vector-icons/Feather';
import type { AnalysisResult, DescriptionMode } from './shared/contracts';
import { checkConnection, defaultApiUrl, describeMedia, normalizeApiUrl } from './src/api';
import { demoResult, formatTimestamp, narration } from './src/demo';
import { pickMedia, prepareMedia, releaseMedia, type SelectedMedia, type PreparedMedia } from './src/media';
import { useSpeech } from './src/hooks/useSpeech';

const C = { bg: '#F6F7F2', paper: '#FFFFFF', ink: '#1B352D', green: '#1D5947', mint: '#E7EFE6', muted: '#52665D', line: '#D8E1D8', orange: '#C26436', peach: '#F8EEE3' };
const modes: { value: DescriptionMode; label: string; icon: React.ComponentProps<typeof Feather>['name'] }[] = [
  { value: 'brief', label: '简洁概览', icon: 'align-left' },
  { value: 'detailed', label: '细致描述', icon: 'eye' },
  { value: 'text', label: '画中文字', icon: 'type' },
];

function Icon({ name, size = 22, color = C.green }: { name: React.ComponentProps<typeof Feather>['name']; size?: number; color?: string }) {
  return <Feather name={name} size={size} color={color} accessible={false} />;
}

function Action({ label, onPress, icon, secondary = false, disabled = false, hint }: {
  label: string; onPress: () => void; icon?: React.ComponentProps<typeof Feather>['name']; secondary?: boolean; disabled?: boolean; hint?: string;
}) {
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityHint={hint} accessibilityState={{ disabled }}
    disabled={disabled} onPress={onPress} style={({ pressed }) => [s.action, secondary && s.actionSecondary, disabled && s.disabled, pressed && s.pressed]}>
    {icon && <Icon name={icon} color={secondary ? C.green : '#FFFFFF'} />}
    <Text style={[s.actionLabel, secondary && { color: C.green }]}>{label}</Text>
  </Pressable>;
}

function DemoScene() {
  return <View style={s.scene} accessible accessibilityLabel="示例插画：窗边桌上有一只绿色茶杯和一盆植物，窗外有太阳。">
    <View style={s.window}><View style={s.sun} /><View style={s.windowBar} /></View>
    <View style={s.table} />
    <View style={s.cupHandle} /><View style={s.cup} />
    <View style={[s.steam, { left: 74 }]} /><View style={[s.steam, { left: 92, top: 73 }]} /><View style={[s.steam, { left: 110 }]} />
    <View style={s.stem} /><View style={[s.leaf, { left: 204, transform: [{ rotate: '-24deg' }] }]} /><View style={[s.leaf, { left: 220, top: 98, transform: [{ rotate: '105deg' }] }]} />
    <View style={s.pot} />
  </View>;
}

function AppContent() {
  const { width } = useWindowDimensions();
  const wide = width >= 850;
  const [page, setPage] = useState<'home' | 'settings'>('home');
  const [mode, setMode] = useState<DescriptionMode>('brief');
  const [resultMode, setResultMode] = useState<DescriptionMode>('brief');
  const [media, setMedia] = useState<SelectedMedia | null>(null);
  const [result, setResult] = useState<AnalysisResult | null>(null);
  const [demo, setDemo] = useState(false);
  const [busy, setBusy] = useState(false);
  const [picking, setPicking] = useState(false);
  const [progress, setProgress] = useState('');
  const [error, setError] = useState('');
  const [question, setQuestion] = useState('');
  const [qa, setQa] = useState<{ question: string; answer: string }[]>([]);
  const [apiUrl, setApiUrl] = useState(defaultApiUrl);
  const [token, setToken] = useState('');
  const [rate, setRate] = useState(1);
  const [autoRead, setAutoRead] = useState(false);
  const [screenReader, setScreenReader] = useState(false);
  const [connection, setConnection] = useState<'unknown' | 'ready' | 'unconfigured' | 'failed'>('unknown');
  const [checking, setChecking] = useState(false);
  const [connectionMessage, setConnectionMessage] = useState('');
  const prepared = useRef<PreparedMedia | null>(null);
  const mediaRef = useRef<SelectedMedia | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const generation = useRef(0);
  const busyRef = useRef(false);
  const pickingRef = useRef(false);
  const speech = useSpeech();
  const preferences = useRef({ autoRead, screenReader, rate });
  preferences.current = { autoRead, screenReader, rate };

  useEffect(() => {
    const updateReader = (enabled: boolean) => {
      preferences.current.screenReader = enabled; setScreenReader(enabled);
      if (enabled) void speech.stop();
    };
    AccessibilityInfo.isScreenReaderEnabled().then(updateReader).catch(() => {});
    const sub = AccessibilityInfo.addEventListener('screenReaderChanged', updateReader);
    return () => { generation.current += 1; sub.remove(); abortRef.current?.abort(); void prepared.current?.cleanup(); if (mediaRef.current) void releaseMedia(mediaRef.current); };
  }, [speech.stop]);
  useEffect(() => {
    const sub = AppState.addEventListener('change', state => { if (state !== 'active') void speech.stop(); });
    return () => sub.remove();
  }, [speech.stop]);
  useEffect(() => {
    if (speech.error && Platform.OS === 'ios') AccessibilityInfo.announceForAccessibility(speech.error);
  }, [speech.error]);

  const announce = (message: string) => { if (Platform.OS === 'ios') AccessibilityInfo.announceForAccessibility(message); };
  const setStatus = (message: string) => { setProgress(message); announce(message); };
  const fail = (message: string) => { setError(message); announce(message); };
  const cancel = () => {
    generation.current += 1;
    abortRef.current?.abort(); abortRef.current = null;
    busyRef.current = false; setBusy(false); setStatus('已取消，你可以重新开始。');
  };
  const clearContent = async () => {
    cancel();
    const previous = prepared.current; prepared.current = null;
    const previousMedia = mediaRef.current; mediaRef.current = null;
    setMedia(null); setResult(null); setDemo(false); setQuestion(''); setQa([]); setError(''); setProgress('');
    await speech.stop(); await previous?.cleanup(); if (previousMedia) await releaseMedia(previousMedia);
  };

  async function select(kind: 'image' | 'video' | 'camera') {
    if (busyRef.current || pickingRef.current) return;
    pickingRef.current = true; setPicking(true);
    const id = ++generation.current;
    setError('');
    try {
      // Preserve the browser's user activation for its system file picker.
      void speech.stop();
      const selected = await pickMedia(kind);
      if (!selected) return;
      if (id !== generation.current) { await releaseMedia(selected); return; }
      void clearContent();
      mediaRef.current = selected; setMedia(selected);
      setStatus(`已选择${selected.type === 'video' ? '视频' : '图片'}。点击“开始描述”后上传识别。`);
    } catch (e) { if (id === generation.current) fail(e instanceof Error ? e.message : '暂时无法打开媒体，请重试。'); }
    finally { pickingRef.current = false; setPicking(false); }
  }

  async function analyze(askedQuestion?: string) {
    if (!media || busyRef.current || pickingRef.current) return;
    busyRef.current = true; setBusy(true); setError('');
    const id = ++generation.current;
    const controller = new AbortController(); abortRef.current = controller;
    let newPrepared: PreparedMedia | null = null;
    try {
      await speech.stop();
      if (id !== generation.current) return;
      normalizeApiUrl(apiUrl);
      if (!prepared.current) {
        setStatus('正在手机上整理画面…');
        newPrepared = await prepareMedia(media, { signal: controller.signal, onProgress: message => { if (id === generation.current) setStatus(message); } });
        if (id !== generation.current) { await newPrepared.cleanup(); return; }
        prepared.current = newPrepared;
      }
      setStatus(askedQuestion ? '正在根据当前画面回答…' : '正在理解画面，整理适合聆听的描述…');
      const payload = prepared.current;
      const response = await describeMedia(apiUrl, token, {
        mediaType: payload.mediaType, frames: payload.frames, durationMs: payload.durationMs, mode,
        ...(askedQuestion ? { question: askedQuestion } : {}),
      }, controller.signal);
      if (id !== generation.current) return;
      setConnection('ready');
      if (askedQuestion) {
        const answer = response.answer || response.summary;
        setQa(current => [...current, { question: askedQuestion, answer }]); setQuestion('');
        setStatus('回答已就绪。');
        if (preferences.current.autoRead && !preferences.current.screenReader && AppState.currentState === 'active') void speech.speak(answer, preferences.current.rate);
      } else {
        setResult(response); setResultMode(mode); setQa([]);
        setStatus('描述已就绪。可以朗读，也可以继续了解细节。');
        if (preferences.current.autoRead && !preferences.current.screenReader && AppState.currentState === 'active') void speech.speak(narration(response, mode), preferences.current.rate);
      }
    } catch (e) {
      if (id === generation.current) { fail(e instanceof Error ? e.message : '描述失败，请稍后重试。'); setProgress(''); }
    } finally {
      if (id === generation.current) { busyRef.current = false; setBusy(false); abortRef.current = null; }
    }
  }

  async function showDemo() {
    void clearContent(); setDemo(true); setResult(demoResult); setResultMode('detailed');
    setStatus('已打开本地示例，点击朗读即可体验。');
  }

  async function testConnection() {
    setChecking(true); setConnectionMessage('正在检查连接…');
    try {
      const configured = await checkConnection(apiUrl, token);
      setConnection(configured ? 'ready' : 'unconfigured');
      const message = configured ? '连接成功，识别服务已配置。' : '服务已连接，识别功能尚未开通，请联系服务提供方完成配置。';
      setConnectionMessage(message); announce(message);
    } catch (e) { setConnection('failed'); const message = e instanceof Error ? e.message : '连接失败。'; setConnectionMessage(message); announce(message); }
    finally { setChecking(false); }
  }

  const readerText = result ? `${demo ? '本地演示。' : ''}${media?.type === 'video' ? '以下描述来自抽样画面，不包含视频声音，可能遗漏短暂动作。' : ''}${narration(result, resultMode)}` : '';
  const errorText = error || speech.error;

  return <SafeAreaView style={s.safe} edges={['top', 'left', 'right', 'bottom']}>
    <StatusBar style="dark" />
    <View style={s.header}><View style={s.headerInner}>
      <View style={s.brand}><View style={s.logo}><Icon name="headphones" color="#FFFFFF" size={25} /></View><View><Text style={s.brandName}>听见画面</Text><Text style={s.brandSub}>让每一幅画面，都有声音</Text></View></View>
      <Pressable accessibilityRole="button" accessibilityLabel="打开连接设置" onPress={() => { void speech.stop(); setPage('settings'); }} style={s.connectionChip}>
        <View style={[s.dot, { backgroundColor: connection === 'ready' ? '#2C7652' : '#8D633C' }]} />
        <Text style={s.small}>{connection === 'ready' ? '服务已连接' : '连接设置'}</Text><Icon name="chevron-right" size={15} />
      </Pressable>
    </View></View>
    <KeyboardAvoidingView style={s.flex} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={s.scroll}>
        <View style={s.content}>
          {page === 'settings' ? <View style={s.settings}>
            <Text style={s.eyebrow}>按你的习惯，轻松聆听</Text>
            <Text accessibilityRole="header" style={s.title}>聆听与连接</Text>
            <Text style={s.body}>设置仅在本次使用中保留。返回首页后可继续浏览当前内容。</Text>
            <View style={s.card}>
              <View style={s.sectionHeading}><Icon name="volume-2" /><Text accessibilityRole="header" style={s.sectionTitle}>朗读偏好</Text></View>
              <Text style={s.label}>朗读速度</Text>
              <View style={s.segment}>{[0.8, 1, 1.2, 1.5].map(value => <Pressable key={value} accessibilityRole="radio" accessibilityState={{ checked: value === rate }} aria-checked={value === rate} accessibilityLabel={`${value}倍速`} onPress={() => { setRate(value); void speech.stop(); }} style={[s.rateButton, rate === value && s.segmentSelected]}><Text style={[s.segmentText, rate === value && s.segmentTextSelected]}>{value}×</Text></Pressable>)}</View>
              <View style={s.switchRow}><View style={s.flex}><Text style={s.label}>描述完成后自动朗读</Text><Text style={s.small}>使用 VoiceOver 或 TalkBack 时自动暂停此功能，避免重叠播报。</Text></View><Switch accessibilityLabel="描述完成后自动朗读" value={autoRead} onValueChange={setAutoRead} trackColor={{ false: '#B3BFB7', true: C.green }} /></View>
              <Action label={speech.speaking ? '停止试听' : '试听朗读声音'} icon={speech.speaking ? 'square' : 'volume-2'} secondary onPress={() => { if (speech.speaking) void speech.stop(); else void speech.speak('你好，我是听见画面。选一张照片，让我把画面讲给你听。', rate); }} />
              <Text style={s.small}>使用设备上的中文语音。iPhone 若没有声音，请关闭静音模式并调高媒体音量。</Text>
            </View>
            <View style={s.card}>
              <View style={s.sectionHeading}><Icon name="link" /><Text accessibilityRole="header" style={s.sectionTitle}>描述服务</Text></View>
              <Text style={s.label}>服务地址</Text>
              <TextInput accessibilityLabel="描述服务地址" style={s.input} editable={!checking && !busy} value={apiUrl} onChangeText={value => { setApiUrl(value); setConnection('unknown'); setConnectionMessage(''); }} autoCapitalize="none" autoCorrect={false} keyboardType="url" placeholder="https://your-server.example.com" placeholderTextColor={C.muted} />
              <Text style={s.small}>真机调试时填写电脑的局域网 IP 地址。正式使用请填写 HTTPS 地址。</Text>
              <Text style={s.label}>访问口令（服务启用时填写）</Text>
              <TextInput accessibilityLabel="描述服务访问口令" style={s.input} editable={!checking && !busy} value={token} onChangeText={value => { setToken(value); setConnection('unknown'); }} secureTextEntry autoCapitalize="none" autoCorrect={false} placeholder="可选，由服务提供方提供" placeholderTextColor={C.muted} />
              <Action label={checking ? '正在检查连接…' : '检查连接'} icon="wifi" secondary disabled={checking || busy} onPress={() => void testConnection()} />
              {!!connectionMessage && <Text accessibilityLiveRegion="polite" role="status" style={s.body}>{connectionMessage}</Text>}
            </View>
            <View style={s.notice}><Icon name="shield" /><Text style={[s.small, s.flex]}>仅在你点击开始描述或发送问题后，将压缩图片或视频抽样画面发送至已配置服务和 AI 提供方。应用不建立浏览历史；清空内容可移除本次会话数据。</Text></View>
            <Action label="返回浏览" icon="arrow-left" onPress={() => setPage('home')} />
          </View> : <>
            <View style={[s.hero, wide && s.heroWide]}>
              <View style={s.flex}><View style={s.eyebrowRow}><View style={s.eyebrowLine} /><Text style={s.eyebrow}>你的随身画面讲述者</Text></View><Text accessibilityRole="header" style={[s.title, wide && s.titleWide]}>把画面，{wide ? '\n' : ''}讲给你听。</Text><Text style={s.heroBody}>一张照片，一段视频。{wide ? '\n' : ''}从整体到细节，听见你感兴趣的内容。</Text></View>
              {wide && <View style={s.heroAside}><DemoScene /><View style={s.sceneBadge}><Icon name="activity" /><Text style={s.small}>从画面，到声音</Text></View></View>}
            </View>
            <View style={[s.columns, wide && s.columnsWide]}>
              <View style={[s.mainColumn, wide && { flex: 1.45 }]}>
                <View style={s.card}>
                  <View style={s.sectionHeading}><View style={s.stepCircle}><Text style={s.stepNumber}>1</Text></View><Text accessibilityRole="header" style={s.sectionTitle}>想听些什么？</Text></View>
                  <View style={s.pickRow}>
                    {([{ kind: 'image', title: '选择照片', text: '照片、截图、表情包', icon: 'image' }, { kind: 'video', title: '选择视频', text: '2 分钟内 · 仅画面，不含声音', icon: 'film' }] as const).map(item => <Pressable key={item.kind} disabled={busy || picking} accessibilityRole="button" accessibilityLabel={item.title} accessibilityHint={item.text} accessibilityState={{ disabled: busy || picking }} onPress={() => void select(item.kind)} style={({ pressed }) => [s.pickCard, pressed && s.pressed, (busy || picking) && s.disabled]}><View style={s.pickIcon}><Icon name={item.icon} size={28} /></View><Text style={s.pickTitle}>{item.title}</Text><Text style={s.pickSub}>{item.text}</Text><Icon name="arrow-up-right" size={19} /></Pressable>)}
                  </View>
                  <Action label="拍一张照片" icon="camera" secondary disabled={busy || picking} onPress={() => void select('camera')} />
                  {media && <View style={s.selectedMedia}>
                    {media.type === 'image' ? <Image source={{ uri: media.uri }} style={s.thumbnail} accessible={false} /> : <View style={s.thumbnail}><Icon name="film" size={26} /></View>}
                    <View style={s.flex}><Text style={s.label}>{media.type === 'video' ? '已选择视频' : '已选择照片'}</Text><Text style={s.small} numberOfLines={2}>{media.name}</Text>{media.durationMs ? <Text style={s.small}>{formatTimestamp(media.durationMs)} · 仅识别抽样画面</Text> : null}</View>
                    <Pressable accessibilityRole="button" accessibilityLabel="移除当前内容" onPress={() => void clearContent()} style={s.iconButton}><Icon name="x" /></Pressable>
                  </View>}
                  <View style={[s.sectionHeading, { marginTop: 12 }]}><View style={s.stepCircle}><Text style={s.stepNumber}>2</Text></View><Text accessibilityRole="header" style={s.sectionTitle}>选择描述方式</Text></View>
                  <View style={s.segment}>{modes.map(item => <Pressable key={item.value} disabled={busy} accessibilityRole="radio" accessibilityLabel={item.label} accessibilityState={{ checked: mode === item.value, disabled: busy }} aria-checked={mode === item.value} onPress={() => setMode(item.value)} style={[s.modeButton, mode === item.value && s.segmentSelected]}><Icon name={item.icon} size={18} color={mode === item.value ? '#FFFFFF' : C.muted} /><Text style={[s.segmentText, mode === item.value && s.segmentTextSelected]}>{item.label}</Text></Pressable>)}</View>
                  <Text style={s.small}>{mode === 'brief' ? '先用几句话，说清画面里的主要内容。' : mode === 'detailed' ? '了解位置、动作、场景和更多可见细节。' : '按阅读顺序，提取画面中清晰可读的文字。'}</Text>
                  <Action label={busy ? '正在描述…' : result && !demo ? '重新描述' : '开始描述'} icon="headphones" disabled={!media || busy || picking} onPress={() => void analyze()} />
                  <Text style={s.privacyText}>点击后上传所选画面进行 AI 识别</Text>
                </View>
                {(!!progress || busy || !!errorText) && <View style={[s.notice, errorText ? { backgroundColor: C.peach } : {}]}>
                  {busy ? <ActivityIndicator color={C.green} /> : <Icon name={errorText ? 'alert-circle' : 'check-circle'} />}
                  <View style={s.flex}><Text accessibilityLiveRegion="polite" role="status" style={s.body}>{errorText || progress}</Text>{busy && <Action label="取消本次描述" icon="x" secondary onPress={cancel} />}</View>
                </View>}
                {result && <View style={s.card}>
                  <View style={s.resultMeta}><View style={s.tag}><Text style={s.tagText}>{demo ? '本地演示' : media?.type === 'video' ? '视频描述' : '图片描述'}</Text></View><Text style={s.small}>{modes.find(item => item.value === resultMode)?.label}</Text></View>
                  {demo && <DemoScene />}
                  <Text accessibilityRole="header" style={s.resultTitle}>{result.title}</Text>
                  <Text style={s.resultSummary}>{result.summary}</Text>
                  {media?.type === 'video' && <Text style={s.explanation}>根据 {prepared.current?.frames.length ?? 0} 张抽样画面描述，不包含声音，可能遗漏短暂动作。</Text>}
                  <Action label={speech.speaking ? '停止朗读' : '朗读这段描述'} icon={speech.speaking ? 'square' : 'volume-2'} onPress={() => { if (speech.speaking) void speech.stop(); else void speech.speak(readerText, rate); }} />
                  <Text style={s.small}>当前语速 {rate}× · 可在“设置”中调整</Text>
                  {result.details.length > 0 && <View style={s.resultSection}><Text accessibilityRole="header" style={s.label}>画面细节</Text>{result.details.map((text, index) => <View key={index} style={s.detailRow}><Text style={s.detailNumber}>{String(index + 1).padStart(2, '0')}</Text><Text style={[s.body, s.flex]}>{text}</Text></View>)}</View>}
                  <View style={s.resultSection}><Text accessibilityRole="header" style={s.label}>画面中的文字</Text><Text style={s.body}>{result.visibleText.length ? result.visibleText.join('\n') : '没有识别到清晰可读的文字。'}</Text></View>
                  {result.timeline.length > 0 && <View style={s.resultSection}><Text accessibilityRole="header" style={s.label}>按时间了解画面</Text>{result.timeline.map((item, index) => <View style={s.detailRow} key={index}><Text style={s.timestamp}>{formatTimestamp(item.timestampMs)}</Text><Text style={[s.body, s.flex]}>{item.description}</Text></View>)}</View>}
                  {result.uncertainties.length > 0 && <View style={s.resultSection}><Text accessibilityRole="header" style={s.label}>需要说明的地方</Text>{result.uncertainties.map((text, index) => <Text key={index} style={s.body}>{text}</Text>)}</View>}
                  {!demo && <View style={s.resultSection}>
                    <Text accessibilityRole="header" style={s.sectionTitle}>还想知道些什么？</Text><Text style={s.small}>每次提问会再次上传当前画面。也可以使用系统键盘的语音输入。</Text>
                    {qa.map((item, index) => <View key={index} style={s.answerCard}><Text style={s.label}>{item.question}</Text><Text style={s.body}>{item.answer}</Text><Action label={`朗读回答 ${index + 1}`} secondary icon="volume-2" onPress={() => void speech.speak(item.answer, rate)} /></View>)}
                    <TextInput accessibilityLabel="关于当前画面的问题" style={[s.input, { minHeight: 88 }]} placeholder="例如：左边的人正在做什么？" placeholderTextColor={C.muted} value={question} onChangeText={setQuestion} maxLength={500} multiline editable={!busy} />
                    <Action label="发送问题" secondary icon="send" disabled={!question.trim() || busy} onPress={() => void analyze(question.trim())} />
                  </View>}
                  <Action label="清空本次内容" icon="trash-2" secondary onPress={() => void clearContent()} />
                </View>}
              </View>
              <View style={[s.sideColumn, wide && { flex: 1 }]}>
                <View style={s.guideCard}><View style={s.sectionHeading}><Icon name="sun" color={C.orange} /><Text accessibilityRole="header" style={s.sectionTitle}>按自己的节奏了解</Text></View>
                  {[['先听整体', '先知道是什么，再决定要不要听更多。'], ['再听细节', '人物的位置、正在发生的事，还有画面里的文字。'], ['随时追问', '针对当前画面提问，了解你在意的部分。']].map(([title, text], index) => <View key={title} style={s.guideRow}><View style={s.guideDot}><Text style={s.guideNumber}>{index + 1}</Text></View><View style={s.flex}><Text style={s.label}>{title}</Text><Text style={s.body}>{text}</Text></View></View>)}
                </View>
                <View style={s.demoCard}><View style={s.sectionHeading}><Icon name="play-circle" /><Text accessibilityRole="header" style={s.sectionTitle}>第一次来？听一个例子</Text></View><Text style={s.body}>用“窗边的一杯热茶”体验描述与朗读，无需上传照片。</Text><Action label="体验示例描述" icon="arrow-right" secondary disabled={busy || picking} onPress={() => void showDemo()} /></View>
                <View style={s.note}><Icon name="heart" size={19} /><Text style={[s.small, s.flex]}>为聆听而设计：大按钮、清晰的操作顺序，支持 VoiceOver 与 TalkBack。</Text></View>
              </View>
            </View>
          </>}
          {page === 'settings' && !!speech.error && <Text accessibilityLiveRegion="polite" style={s.explanation}>{speech.error}</Text>}
          <Text style={s.footer}>听见画面 · 多一点理解，多一点自由</Text>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
    {speech.speaking && <View style={s.speakingBar}><View style={s.row}><Icon name="volume-2" /><Text style={s.label}>正在朗读</Text></View><Pressable accessibilityRole="button" accessibilityLabel="立即停止朗读" onPress={() => void speech.stop()} style={s.stopButton}><Icon name="square" size={18} /><Text style={s.label}>停止</Text></Pressable></View>}
    <View style={s.nav}>{([{ value: 'home', label: '浏览', icon: 'grid' }, { value: 'settings', label: '设置', icon: 'sliders' }] as const).map(item => <Pressable key={item.value} accessibilityRole="tab" accessibilityState={{ selected: page === item.value }} aria-selected={page === item.value} accessibilityLabel={item.label} onPress={() => { void speech.stop(); setPage(item.value); }} style={s.navItem}><Icon name={item.icon} size={22} color={page === item.value ? C.green : C.muted} /><Text style={[s.navText, page === item.value && { color: C.green, fontWeight: '700' }]}>{item.label}</Text>{page === item.value && <View style={s.navIndicator} />}</Pressable>)}</View>
  </SafeAreaView>;
}

export default function App() { return <SafeAreaProvider><AppContent /></SafeAreaProvider>; }

const s = StyleSheet.create({
  flex: { flex: 1 }, safe: { flex: 1, backgroundColor: C.bg },
  header: { backgroundColor: C.bg, borderBottomWidth: 1, borderBottomColor: C.line, paddingHorizontal: 22 },
  headerInner: { minHeight: 92, maxWidth: 1100, width: '100%', alignSelf: 'center', flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  brand: { flexDirection: 'row', alignItems: 'center', gap: 12, flexShrink: 1 }, logo: { width: 46, height: 46, backgroundColor: C.green, borderRadius: 15, alignItems: 'center', justifyContent: 'center' },
  brandName: { color: C.ink, fontSize: 21, fontWeight: '700', letterSpacing: 1 }, brandSub: { color: C.muted, fontSize: 11, marginTop: 4, lineHeight: 18 },
  connectionChip: { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 10, minHeight: 48, flexShrink: 0 }, dot: { width: 7, height: 7, borderRadius: 4 },
  scroll: { flexGrow: 1, padding: 22 }, content: { maxWidth: 1060, width: '100%', alignSelf: 'center' },
  hero: { paddingTop: 14, paddingBottom: 30, gap: 26 }, heroWide: { flexDirection: 'row', paddingVertical: 36, alignItems: 'center' },
  eyebrowRow: { flexDirection: 'row', alignItems: 'center', gap: 10, marginBottom: 14 }, eyebrowLine: { width: 24, height: 2, backgroundColor: C.orange }, eyebrow: { fontSize: 13, letterSpacing: 2, lineHeight: 22, color: C.green, fontWeight: '600' },
  title: { fontSize: 34, lineHeight: 48, color: C.ink, fontWeight: '700', letterSpacing: 1, marginBottom: 12 }, titleWide: { fontSize: 48, lineHeight: 64 }, heroBody: { fontSize: 16, color: C.muted, lineHeight: 28, maxWidth: 490 },
  heroAside: { width: 340, alignItems: 'center', gap: 10 }, sceneBadge: { flexDirection: 'row', gap: 8, alignItems: 'center' },
  columns: { gap: 22 }, columnsWide: { flexDirection: 'row', alignItems: 'flex-start', gap: 28 }, mainColumn: { gap: 20, minWidth: 0 }, sideColumn: { gap: 20, minWidth: 0 },
  card: { backgroundColor: C.paper, borderRadius: 22, borderWidth: 1, borderColor: C.line, padding: 22, gap: 16 }, sectionHeading: { flexDirection: 'row', gap: 10, alignItems: 'center' }, sectionTitle: { fontSize: 18, lineHeight: 28, color: C.ink, fontWeight: '700', flexShrink: 1 },
  stepCircle: { width: 26, height: 26, borderRadius: 13, backgroundColor: C.mint, alignItems: 'center', justifyContent: 'center' }, stepNumber: { color: C.green, fontWeight: '700', fontSize: 13 },
  pickRow: { flexDirection: 'row', gap: 12 }, pickCard: { flex: 1, alignItems: 'flex-start', backgroundColor: '#F5F8F3', borderRadius: 16, borderWidth: 1, borderColor: C.line, padding: 16, gap: 8, minHeight: 176 }, pickIcon: { width: 46, height: 46, justifyContent: 'center', marginBottom: 3 }, pickTitle: { fontSize: 20, lineHeight: 28, fontWeight: '700', color: C.ink }, pickSub: { fontSize: 12, lineHeight: 19, color: C.muted, flex: 1 },
  action: { minHeight: 56, borderRadius: 12, backgroundColor: C.green, borderWidth: 1, borderColor: C.green, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 16, paddingVertical: 13, gap: 10, flexDirection: 'row' }, actionSecondary: { backgroundColor: '#FFFFFF', borderColor: C.line }, actionLabel: { color: '#FFFFFF', fontSize: 16, fontWeight: '700', lineHeight: 26, flexShrink: 1 }, disabled: { opacity: 0.46 }, pressed: { opacity: 0.72 },
  segment: { flexDirection: 'row', padding: 4, borderRadius: 12, backgroundColor: '#F0F3EE', gap: 3 }, modeButton: { flex: 1, minHeight: 62, paddingHorizontal: 2, paddingVertical: 9, alignItems: 'center', justifyContent: 'center', gap: 5, borderRadius: 9 }, segmentSelected: { backgroundColor: C.green }, segmentText: { color: C.muted, fontSize: 13, lineHeight: 21, fontWeight: '600' }, segmentTextSelected: { color: '#FFFFFF' },
  privacyText: { fontSize: 12, lineHeight: 19, textAlign: 'center', color: C.muted, marginTop: -7 }, body: { fontSize: 16, lineHeight: 28, color: C.muted }, small: { fontSize: 13, lineHeight: 22, color: C.muted }, label: { fontSize: 16, lineHeight: 26, color: C.ink, fontWeight: '600' },
  selectedMedia: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 12, borderRadius: 12, backgroundColor: C.mint }, thumbnail: { width: 55, height: 55, borderRadius: 8, justifyContent: 'center', alignItems: 'center' }, iconButton: { minWidth: 48, minHeight: 48, alignItems: 'center', justifyContent: 'center' },
  notice: { backgroundColor: C.mint, padding: 18, borderRadius: 14, flexDirection: 'row', alignItems: 'flex-start', gap: 12 },
  guideCard: { padding: 26, borderRadius: 22, backgroundColor: '#EAF0E6', gap: 26 }, guideRow: { flexDirection: 'row', gap: 14 }, guideDot: { width: 28, height: 28, borderRadius: 14, borderWidth: 1, borderColor: '#ACBEA8', alignItems: 'center', justifyContent: 'center', marginTop: 1 }, guideNumber: { color: C.green, fontSize: 12 },
  demoCard: { backgroundColor: C.peach, borderRadius: 20, padding: 24, gap: 16 }, note: { paddingHorizontal: 8, flexDirection: 'row', gap: 10, alignItems: 'flex-start' },
  resultMeta: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }, tag: { backgroundColor: C.mint, paddingHorizontal: 10, paddingVertical: 5, borderRadius: 6 }, tagText: { color: C.green, fontSize: 12, fontWeight: '600' }, resultTitle: { fontSize: 26, fontWeight: '700', lineHeight: 38, color: C.ink }, resultSummary: { fontSize: 19, lineHeight: 34, color: C.ink }, explanation: { padding: 14, backgroundColor: C.peach, color: '#6D482A', fontSize: 14, lineHeight: 25, borderRadius: 10 },
  resultSection: { borderTopWidth: 1, borderTopColor: C.line, paddingTop: 18, gap: 12 }, detailRow: { flexDirection: 'row', alignItems: 'flex-start', gap: 12 }, detailNumber: { color: C.green, fontSize: 13, lineHeight: 28, fontWeight: '700' }, timestamp: { color: C.green, fontSize: 14, lineHeight: 28, fontVariant: ['tabular-nums'] }, answerCard: { backgroundColor: C.bg, borderRadius: 12, padding: 16, gap: 12 },
  input: { minHeight: 56, borderRadius: 10, padding: 14, color: C.ink, borderWidth: 1, borderColor: '#96AA9D', backgroundColor: '#FFFFFF', fontSize: 16, lineHeight: 26, textAlignVertical: 'top' }, settings: { maxWidth: 680, width: '100%', alignSelf: 'center', gap: 20, paddingTop: 16 }, rateButton: { flex: 1, minHeight: 52, borderRadius: 9, alignItems: 'center', justifyContent: 'center' }, switchRow: { flexDirection: 'row', alignItems: 'center', gap: 20, paddingVertical: 10 },
  footer: { color: C.muted, fontSize: 12, lineHeight: 20, textAlign: 'center', paddingTop: 35, paddingBottom: 20, letterSpacing: 1 },
  nav: { borderTopWidth: 1, borderTopColor: C.line, backgroundColor: C.paper, flexDirection: 'row', justifyContent: 'center', gap: 50, paddingTop: 5 }, navItem: { minHeight: 62, minWidth: 96, alignItems: 'center', justifyContent: 'center', gap: 2, padding: 8 }, navText: { fontSize: 12, lineHeight: 20, color: C.muted }, navIndicator: { position: 'absolute', bottom: 0, height: 3, width: 28, borderRadius: 2, backgroundColor: C.green },
  row: { flexDirection: 'row', alignItems: 'center', gap: 10 }, speakingBar: { backgroundColor: C.mint, paddingHorizontal: 24, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', minHeight: 60 }, stopButton: { flexDirection: 'row', alignItems: 'center', gap: 8, minHeight: 48, paddingHorizontal: 12 },
  scene: { width: 300, height: 192, maxWidth: '100%', alignSelf: 'center', overflow: 'hidden', borderRadius: 28, backgroundColor: '#E9EEE3' }, window: { position: 'absolute', top: 18, left: 57, width: 178, height: 132, borderRadius: 32, borderWidth: 7, borderColor: '#FFFFFF', backgroundColor: '#D0E3E1' }, sun: { position: 'absolute', left: 105, top: 18, width: 31, height: 31, borderRadius: 16, backgroundColor: '#E5B969' }, windowBar: { position: 'absolute', left: 80, top: 0, width: 6, height: 125, backgroundColor: '#FFFFFF' }, table: { position: 'absolute', top: 160, height: 32, width: '100%', backgroundColor: '#D0BFA1' }, cup: { position: 'absolute', top: 115, left: 65, width: 64, height: 47, borderBottomLeftRadius: 17, borderBottomRightRadius: 17, backgroundColor: C.green }, cupHandle: { position: 'absolute', top: 119, left: 115, width: 32, height: 29, borderRadius: 14, borderWidth: 7, borderColor: C.green }, steam: { position: 'absolute', top: 78, width: 3, height: 25, borderRadius: 3, backgroundColor: '#829B8B' }, stem: { position: 'absolute', left: 222, top: 89, width: 3, height: 53, backgroundColor: C.green }, leaf: { position: 'absolute', left: 204, top: 83, width: 24, height: 13, borderTopLeftRadius: 15, borderBottomRightRadius: 15, backgroundColor: '#5C8060' }, pot: { position: 'absolute', top: 135, left: 205, width: 38, height: 29, borderBottomLeftRadius: 10, borderBottomRightRadius: 10, backgroundColor: '#BA815B' },
});
