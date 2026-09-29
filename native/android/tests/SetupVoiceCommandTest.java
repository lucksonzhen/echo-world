package com.tingjian.assistant;

public final class SetupVoiceCommandTest {
    private static int count;
    private static void check(String text,SetupVoiceCommand.Kind kind) {
        if (SetupVoiceCommand.parse(text).kind!=kind) throw new AssertionError(text); count++;
    }
    public static void main(String[] args) {
        check("小助手，下一步",SetupVoiceCommand.Kind.NEXT);
        check("小 助 手 上 一 步",SetupVoiceCommand.Kind.BACK);
        check("小助手 上 一部",SetupVoiceCommand.Kind.BACK); // Actual bundled decoder homophone.
        check("小助手帮助",SetupVoiceCommand.Kind.HELP);
        check("小助手粘贴密钥",SetupVoiceCommand.Kind.PASTE);
        check("小助手测试连接",SetupVoiceCommand.Kind.TEST);
        check("小助手同意上传",SetupVoiceCommand.Kind.CONSENT);
        check("小助手确认操作",SetupVoiceCommand.Kind.CONFIRM);
        check("小助手取消操作",SetupVoiceCommand.Kind.CANCEL);
        check("小助手开启本项",SetupVoiceCommand.Kind.ENABLE);
        check("小助手暂不开启",SetupVoiceCommand.Kind.SKIP);
        check("小助手打开设置",SetupVoiceCommand.Kind.SETTINGS);
        check("小助手稍后配置",SetupVoiceCommand.Kind.DEFER);
        check("小助手暂停朗读",SetupVoiceCommand.Kind.MUTE);
        for (String text:new String[]{"确认操作","我不同意上传","小助手不同意上传","听到小助手下一步后执行","小助手帮我把密钥念出来","小助手下一步是什么","小助手确认操作然后上传","小助手",""}) check(text,SetupVoiceCommand.Kind.NONE);
        String[] order={"第一项","第二项","第三项","第四项","第五项"};
        int[] indices={2,3,1,0,4};
        for (int i=0;i<order.length;i++) {
            SetupVoiceCommand result=SetupVoiceCommand.parse("小助手选择"+order[i]);
            if (result.kind!=SetupVoiceCommand.Kind.PROVIDER || result.providerIndex!=indices[i]) throw new AssertionError(order[i]); count++;
        }
        check("小助手选择深度求索",SetupVoiceCommand.Kind.PROVIDER);
        check("小助手选择第六项",SetupVoiceCommand.Kind.NONE);
        for (String text:new String[]{"下一步","选择第二项","确认操作","同意上传","小助手下一步"}) {
            if (SetupVoiceCommand.parseInput(text,true).kind==SetupVoiceCommand.Kind.NONE) throw new AssertionError("explicit window: "+text); count++;
        }
        for (String text:new String[]{"下一步","选择第二项","确认操作","同意上传"}) {
            if (SetupVoiceCommand.parseInput(text,false).kind!=SetupVoiceCommand.Kind.NONE) throw new AssertionError("passive input: "+text); count++;
        }
        for (String text:new String[]{"我不同意上传","确认操作然后上传","下一步是什么","帮我朗读密钥","","小助手"}) {
            if (SetupVoiceCommand.parseInput(text,true).kind!=SetupVoiceCommand.Kind.NONE) throw new AssertionError("unknown input: "+text); count++;
        }
        System.out.println("Setup voice command: "+count+" checks passed");
    }
}
