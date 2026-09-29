package com.tingjian.assistant;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Synthetic fixed audio, actual bundled decoder, production endpoint boundaries. No microphone/API. */
public final class SetupVoiceModelInstrumentation extends Instrumentation {
    private static final String[][] CASES = {
        {"next", "NEXT"}, {"back", "BACK"}, {"provider", "PROVIDER"}, {"help", "HELP"},
        {"paste", "PASTE"}, {"test", "TEST"}, {"consent", "CONSENT"},
        {"confirm", "CONFIRM"}, {"cancel", "CANCEL"}, {"skip", "SKIP"}
    };
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() { new Thread(this::runFixtures, "setup-audio-fixtures").start(); }
    private void runFixtures() {
        int passed=0; StringBuilder report=new StringBuilder();
        try (Model model=new Model(OfflineModelStore.prepare(getTargetContext()).getAbsolutePath())) {
            for (String[] fixture:CASES) {
                List<String> events=new ArrayList<>(); List<String> words=new ArrayList<>();
                try (InputStream input=getContext().getAssets().open("setup-commands/"+fixture[0]+".wav");
                        Recognizer recognizer=new Recognizer(model,16000f)) {
                    short[] pcm=VoiceModelInstrumentation.readPcmWave(input), frame=new short[1600];
                    for (int offset=0;offset<pcm.length;offset+=frame.length) {
                        int count=Math.min(frame.length,pcm.length-offset);
                        System.arraycopy(pcm,offset,frame,0,count);
                        if (recognizer.acceptWaveForm(frame,count)) {
                            String text=new JSONObject(recognizer.getResult()).optString("text", "");
                            words.add(text);
                            SetupVoiceCommand action=SetupVoiceCommand.parse(text);
                            if (action.kind!=SetupVoiceCommand.Kind.NONE) events.add(action.kind.name());
                        }
                    }
                    // Forced EOF is diagnostic only: production acts on endpoints, never partial results.
                    words.add("tail="+new JSONObject(recognizer.getFinalResult()).optString("text", ""));
                    boolean ok=events.size()==1 && fixture[1].equals(events.get(0));
                    if (ok) passed++;
                    String result=(ok?"PASS ":"FAIL ")+fixture[0]+": "+events+"; "+words+"\n";
                    report.append(result); Bundle status=new Bundle(); status.putString("stream",result); sendStatus(0,status);
                }
            }
        } catch (Exception | LinkageError error) { report.append(error.getClass().getSimpleName()).append(':').append(error.getMessage()); }
        Bundle summary=new Bundle(); summary.putInt("passed",passed); summary.putInt("failed",CASES.length-passed);
        summary.putString("stream",report.toString()); finish(passed==CASES.length?Activity.RESULT_OK:Activity.RESULT_CANCELED,summary);
    }
}
