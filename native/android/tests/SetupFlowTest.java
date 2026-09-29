package com.tingjian.assistant;

public final class SetupFlowTest {
    private static int passed;
    private static void check(boolean ok) { if (!ok) throw new AssertionError("Setup flow check " + (passed+1)); passed++; }
    public static void main(String[] args) {
        check(SetupFlow.restore("",false,false,false,false)==SetupFlow.Step.PROVIDER);
        for (SetupFlow.Step step:SetupFlow.Step.values()) {
            check(SetupFlow.after(step,false)==step); // Failed validation, permission denial and speech completion cannot advance.
            check(SetupFlow.after(step,true)==(step==SetupFlow.Step.DONE ? step : SetupFlow.Step.values()[step.ordinal()+1]));
        }
        check(SetupFlow.restore("KEY",false,false,false,false)==SetupFlow.Step.KEY);
        check(SetupFlow.restore("DONE",false,true,true,true)==SetupFlow.Step.PROVIDER);
        check(SetupFlow.restore("IMAGES",true,false,true,true)==SetupFlow.Step.TEST);
        check(SetupFlow.restore("DONE",true,true,false,true)==SetupFlow.Step.CONSENT);
        check(SetupFlow.restore("VOICE",true,true,true,false)==SetupFlow.Step.ACCESSIBILITY);
        check(SetupFlow.restore("IMAGES",true,true,true,true)==SetupFlow.Step.IMAGES);
        check(SetupFlow.restore("unknown",true,true,true,true)==SetupFlow.Step.VOICE);
        check(!SetupFlow.needsGuide(true,true,true,false));
        check(SetupFlow.needsGuide(false,true,true,false));
        check(SetupFlow.needsGuide(true,false,true,false));
        check(SetupFlow.needsGuide(true,true,false,false));
        check(SetupFlow.needsGuide(true,true,true,true));
        System.out.println("Setup flow: " + passed + " checks passed");
    }
}
