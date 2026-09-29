package com.tingjian.assistant;

/** Strict, wake-prefixed setup actions. Never interprets unknown speech as a value or consent. */
public final class SetupVoiceCommand {
    public enum Kind { NONE, NEXT, BACK, HELP, SKIP, PASTE, TEST, CONSENT, ENABLE, SETTINGS, CONFIRM, CANCEL, DEFER, MUTE, PROVIDER }
    public final Kind kind;
    public final int providerIndex;
    private SetupVoiceCommand(Kind kind,int index) { this.kind=kind; providerIndex=index; }
    /** Bare commands are accepted only during an explicitly opened listening window. */
    public static SetupVoiceCommand parseInput(String raw,boolean explicitListening) {
        String value=VoiceCommandRouter.normalize(raw);
        return parse(explicitListening && !value.startsWith("小助手") ? "小助手"+value : value);
    }
    public static SetupVoiceCommand parse(String raw) {
        String value=VoiceCommandRouter.normalize(raw);
        if (!value.startsWith("小助手")) return new SetupVoiceCommand(Kind.NONE,-1);
        value=value.substring(3);
        String[] options={"选择第四项","选择第三项","选择第一项","选择第二项","选择第五项"};
        for (int i=0;i<options.length;i++) if (value.equals(options[i])) return new SetupVoiceCommand(Kind.PROVIDER,i);
        if (value.equals("选择gemini") || value.equals("选择谷歌")) return new SetupVoiceCommand(Kind.PROVIDER,2);
        if (value.equals("选择deepseek") || value.equals("选择深度求索")) return new SetupVoiceCommand(Kind.PROVIDER,3);
        Kind kind;
        switch(value) {
            case "下一步": case "继续配置": kind=Kind.NEXT; break;
            case "上一步": case "上一部": case "返回上一步": kind=Kind.BACK; break;
            case "重听": case "再说一遍": case "帮助": kind=Kind.HELP; break;
            case "跳过": case "暂不开启": case "暂不调整": kind=Kind.SKIP; break;
            case "粘贴": case "粘贴密钥": kind=Kind.PASTE; break;
            case "测试连接": kind=Kind.TEST; break;
            case "同意上传": kind=Kind.CONSENT; break;
            case "开启本项": kind=Kind.ENABLE; break;
            case "打开设置": kind=Kind.SETTINGS; break;
            case "确认操作": kind=Kind.CONFIRM; break;
            case "取消操作": kind=Kind.CANCEL; break;
            case "稍后配置": case "返回主界面": kind=Kind.DEFER; break;
            case "暂停朗读": case "停止朗读": kind=Kind.MUTE; break;
            default: kind=Kind.NONE;
        }
        return new SetupVoiceCommand(kind,-1);
    }
}
