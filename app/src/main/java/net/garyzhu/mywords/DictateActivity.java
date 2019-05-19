package net.garyzhu.mywords;

import java.util.HashMap;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.ArrayBlockingQueue;

import net.garyzhu.mywords.R;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

public class DictateActivity extends Activity implements OnInitListener {
	/**
	 * a FIFO queue containing a list of sentences to be spoken.
	 * The string has the first character as 1 or 2, indicating whether this is an emphasis sentence.
	 */
	private ArrayBlockingQueue<String>  speechQueue = null;
	private TextView result;   
    private TextToSpeech tts;    
    private int  utterCount;
    private Button speak_button;
	private ArrayAdapter<String> arrayAdapter = null;
	
	// a flag for SR to know what to expect
	// if false, means expecting next sentence, if true, expecting command
	private boolean expectingCommand = false;
	
	private boolean speakPrompt = false;
	
	public boolean isSpeakPrompt() {
		return speakPrompt;
	}
	public void setSpeakPrompt(boolean speakPrompt) {
		this.speakPrompt = speakPrompt;
	}
	public boolean isExpectingCommand() {
		return expectingCommand;
	}
	public void setExpectingCommand(boolean expectingCommand) {
		this.expectingCommand = expectingCommand;
	}

	private AudioManager audioManager;
	private int normalVol = 0;
	private int maxVol = 0;
	private static Handler mHandler = null;

	MySpeechRecognitionListener speechListener = null;
	SpeechRecognizer sr = null;
	
	protected void addSpeech(String s) {
		synchronized(speechQueue) {
			speechQueue.add(s);
		}
	}
	protected void addChoice(String s) {
		synchronized(arrayAdapter) {
			arrayAdapter.add(s);
		}
	}
	protected int getChoiceLength() {
		synchronized(arrayAdapter) {
			return  arrayAdapter.getCount();
		}
	}
	protected void onStart() {
		super.onStart();
		this.setVolumeControlStream(AudioManager.STREAM_MUSIC);
		muteSystemAudio();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle action bar item clicks here. The action bar will
		// automatically handle clicks on the Home/Up button, so long
		// as you specify a parent activity in AndroidManifest.xml.
		int id = item.getItemId();
		if (id == R.id.action_close) {
			Log.d("onDone", "Menu Close  called");

			this.finishAndRemoveTask();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
	    super.onConfigurationChanged(newConfig);

	    // Checks the orientation of the screen
	    if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
	        myLog("onConfig", "landscape");
	    } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT){
	        myLog("onConfig", "portrait");
	    }
	    // re-paint result view
	    //ListView lv = (ListView) findViewById(R.id.choiceView);
    	//lv.setAdapter(arrayAdapter);
	}
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		mHandler = new Handler() {
	        @Override
	        public void handleMessage(Message msg) {
	            String text = (String)msg.obj;
	            if (text != null && text.equals("StartListening")) {
	            	// doesn't matter listen to what
	            	// no change on result list and isExpectingCommand flag
	            	// simply start listening
	            	startDictating(null);
	            }  else if (text.startsWith("choice ")) {
	            	// voice command here
	            	String comm = text.substring(7);
	            	int idx = Integer.parseInt(comm);
	            	if (idx < arrayAdapter.getCount()) {
	            		// make it 0 based choice
	            		chosen(idx - 1);
	            	} else if (idx == MySpeechRecognitionListener.COMMAND_NONE) {
	            		// some high number so the text is nor added to result view
	            		chosen(100); 	            		
	            	} else {
	            		speakPrompt = true;
	            		expectingCommand = true;
	            		String numChoice = " 1 to 5 ";
						int x  = getChoiceLength() - 1;
						if (x == 1) {
							numChoice = " 1 ";
						} else {
							numChoice = " 1 to " + x + " ";
						}
	            		speakOut("Cannot recognize your choice " + comm + ", please say a number: " + numChoice + ", or none of the above", TextToSpeech.QUEUE_FLUSH);
	            	}
	            } else {
	            	// all other text, simply say it and then let utterancelistner onDone
	            	// to start listener again, assuming all conditions are good.
	            	speakOut(text, TextToSpeech.QUEUE_FLUSH);
	            }
	        }
		};
		
		setContentView(R.layout.activity_dictate);
		
		utterCount = 0;
		/**
		 * initialize choice queue
		 */
		arrayAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1);
		/**
		 * initialize speaking queue, max 8 choice phrases
		 */
		speechQueue = new ArrayBlockingQueue<String>(14);
		
		/**
		 * initialize speech recognizer
		 */
		speechListener = new MySpeechRecognitionListener(this);		
		sr = SpeechRecognizer.createSpeechRecognizer(getApplicationContext());
		sr.setRecognitionListener(speechListener);
		
		/**
		 * this will start TTS initialization, when the initialization is done, 
		 * #onInit(status) will be called.
		 */
		tts = new TextToSpeech(this, this);
		
		result = (TextView)findViewById(R.id.comp_result);
		result.setText("<start>  ");
		
		audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
		normalVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
		maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);	
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.dictate, menu);
		return true;
	}

	@Override
	public void onResume() {
		super.onResume();
		myLog("onResume", "on resume called?");
        // We get the ListView component from the layout
    	ListView lv = (ListView) findViewById(R.id.choiceView);   	
    	lv.setAdapter(arrayAdapter);
    	
    	// React to user clicks on item
	    lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
	         public void onItemClick(AdapterView<?> parentAdapter, View view, int position,  long id) {
	        	 // pick up the selected string text and put it into result view
	             chosen(position);
	         }
	    });
   	}
	
	/**
	 * with multiple choices of heard words, a user picked up one.
	 * @param i    0 based position for chosen words
	 */
	private void chosen(int i) {
		myLog("onChosen", "chose " + i);
		
		// we have recognized the command, so set to expect sentence
		setExpectingCommand(false);
		synchronized(speechQueue) {
			tts.stop();
			speakPrompt = false;
			if (speechQueue.peek() != null) {
				speechQueue.clear();
			}
		}
		
		tts.setSpeechRate(0.5f);
		tts.setPitch(1.0f);
		audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, normalVol, 0);

		String sayString = "Next!";
		synchronized(arrayAdapter) {
			if (i < arrayAdapter.getCount()) {
				// a number is chosen, put that string to result view and 
			    String thisStr = arrayAdapter.getItem(i);
		        int startPos = thisStr.indexOf(":");
		        if (startPos >= 0) {	            	 
		            thisStr = thisStr.substring(startPos + 1);		             
		            result.append(thisStr + ".   ");
		        }
			} else {
		        sayString = "OK, forget about this one, let's try again";
			}
			// the selected sentence is in result view, or "none of the above
			// either case, clear the selection list
	        arrayAdapter.clear();
		}
		processSpeech("P " + sayString);
	}

	protected void sendMessage(String msgStr) {
		if (mHandler == null) {
			myLog("msg", "No message handler to send MSG=" + msgStr);
			return;
		}
		Message msg = new Message();
		String textTochange = msgStr;
		msg.obj = textTochange;
		mHandler.sendMessage(msg);
	}
	
	@Override 
	public void onDestroy() {
		tts.stop();
		speakPrompt = false;
		sr.stopListening();
		sr.destroy();
		speechQueue.clear();
		tts.setSpeechRate(2.0f);
		mHandler = null;
		speakOut("Good Bye!", TextToSpeech.QUEUE_FLUSH);
		try {
			// let above speech done
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			// 
			e.printStackTrace();
		}
		tts.stop();
		tts.shutdown();
		enableSystemAudio();
		super.onDestroy();
	}
	
	/**
	 * this method is called every time the speak button is clicked or invoked 
	 * after speech recognition is done and waiting for the next.
	 */
	public void startDictating(View view) {
		if (view != null) {
			// this is when a user clicked the button
			speak_button = (Button)findViewById(R.id.bt_speak);        
	        speak_button.setEnabled(false);
	        // empty all queue and set to listen to sentence.
			setExpectingCommand(false);
			speakPrompt = false;
			speechQueue.clear();
			arrayAdapter.clear();
			// just speak the prompt, and utterancelistner onDone method will launch SR when 
			// speechQueue is empty.
			processSpeech("P  OK, say something!");
			return;
		}
        
        result = (TextView)findViewById(R.id.comp_result);

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,  RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        // when beep is played, it would be shorted than 2 seconds, we should expect input more than
        // 2 seconds, that would prevent beep to be taken for speech recognition, and hence ERROR_NO_MATCH error
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000);
        // these parameters are trying to allow more silent time
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8000);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000);
        sr.startListening(intent);
        speakPrompt = false;
        final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME);
        tg.startTone(ToneGenerator.TONE_PROP_PROMPT, 50);
    }
    
	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
	@Override
	public void onInit(int status) {
    	myLog("onUtter", "TTS init called");
        if (tts != null && status == TextToSpeech.SUCCESS) {      
        	int result = 0;
            if (tts.getLanguage() != null) {
            	myLog("onUtter", "system default: " + tts.getLanguage().getDisplayLanguage() + " (" + tts.getLanguage().getDisplayCountry() + ")");
            }  else {
            	myLog("getLang", "get null in getLanguage");
            }
           
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        		myError("onUtter", "This Language is not supported " + result + " , return null tts");	
        			tts = null;
            }
       } else {
            tts = null;
       }
       if (tts != null) {

    	   if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) {
    		   UtteranceProgressListener ul = new MyUtteranceProgressListener();
    		   tts.setOnUtteranceProgressListener(ul);
    		   myLog("onUtter", "set up utterance listener");
    	   }
       }
       
       // set up audioManager
       
	}
	
	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
	private class MyUtteranceProgressListener extends UtteranceProgressListener {
		/**
		 * this method is called each time a TTS speech is done, we may have several sentences
		 * queued in speechQueue, this method will de-queue and speak the next sentences
		 * until the speechQueue is empty. After the queue is empty, it will invoke
		 * SpeechRecognizer to listen.  This method does not have knowledge 
		 * of what to listen and what was said.
		 */
		@Override
        public void onDone(String utteranceId)
        {			
			String sayString = null;
			synchronized(speechQueue) {
				if (speechQueue.isEmpty() == false) {
					// after said one thing, retrieve another phrase to say.
					sayString = speechQueue.poll();
				}
			}
			if (sayString != null) {
				// adjust pitch, volume and speed, and say something
				processSpeech(sayString);
			}  else {
				// speech queue is empty,  go to listening mode
				normalVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
				audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, normalVol, 0);
				tts.setSpeechRate(1.0f);
	    		tts.setPitch(1.0f);
	    		if (speakPrompt == true) {
	    			sendMessage("StartListening");
	    			myLog("onDone", "Just said prompt, listening next one");
	    		}
			} 
    		myLog("onUtter", "Exit utterance onDone " + utteranceId);
        }

        @Override
        public synchronized void onError(String utteranceId)
        {
        	myError("onUtter", "Failed");
        }

        @Override
        public void onStart(String utteranceId)
        {
        	myLog("onUtter", " onStart " + utteranceId);
        }
	} // end of MyUtteranceProgressListener  class definition
	
	
	protected void processSpeech(String sayString) {
		speakPrompt = false;
		
		String  f = sayString.substring(0, 1);
		sayString = sayString.substring(1);
		int s = normalVol + 2;
		if (f.equals("1")) {
			
			if (s > maxVol) {
				s = maxVol;
			}
			audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, s, 0);
			tts.setSpeechRate(0.8f);
			tts.setPitch(0.8f);
			myLog("onUtter", "speech slow and volume=" + s);
		} else if (f.equals("P")) {
			tts.stop();
			try {
				// make sure the current speech is completely done, while speechPrompt is false
				Thread.sleep(100);
				myLog("onUtter", "after sleep with speakPrompt = false");
			} catch (InterruptedException e) { }
			speakPrompt = true;
			s = normalVol;
			audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, s, 0);
			tts.setPitch(1.0f);
			tts.setSpeechRate(1.2f);
			myLog("onUtter", "speech fast and volume=" + s);
		} else {
			s = normalVol - 1;
			if (s < 4) {
				s = normalVol;
			}
			audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, s, 0);
			tts.setPitch(1.0f);
			tts.setSpeechRate(1.8f);
			myLog("onUtter", "speech fast and volume=" + s);
		}
		speakOut(sayString, TextToSpeech.QUEUE_FLUSH);
	}
	
	protected void myError(String tag, String msg) {
		StackTraceElement se[] = Thread.currentThread().getStackTrace(); 
		int i = 3;
		if (se[i].getMethodName().startsWith("access$")) {
			i = 4;
		}

		String x =  se[i].getClassName() + "->" +
		             se[i].getMethodName() + ":" + se[i].getLineNumber()+ ":   " + msg;
		Log.e(tag, x);
	}
	
	protected void myLog(String tag, String msg) {
		StackTraceElement se[] = Thread.currentThread().getStackTrace();
		int i = 3;
		if (se[i].getMethodName().startsWith("access$")) {
			// skipping static method access
			i = 4;
		}
		String x =  se[i].getClassName() + "->" +
		             se[i].getMethodName() + ":" + se[i].getLineNumber()+ ":   " + msg;
		Log.d(tag, x);
	}
	
	protected void speakOut(String speakTxt, int queueMode) {
		HashMap<String, String> hm = new HashMap<String, String>();
		utterCount++;
		hm.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "Utterance " + utterCount);
		tts.speak(speakTxt, queueMode, hm);
	}
	
	@Override
	public void onPause() {
		super.onPause();
		Context context = getApplicationContext();
		ActivityManager am = (ActivityManager) context
				.getSystemService(Context.ACTIVITY_SERVICE);
		List<RunningTaskInfo> taskInfo = am.getRunningTasks(1);
		if (!taskInfo.isEmpty()) {
			ComponentName topActivity = taskInfo.get(0).topActivity;
			if (!topActivity.getPackageName().equals(context.getPackageName())) {
				myLog("pause", "Exit here " + topActivity.getPackageName()
						+ "-" + context.getPackageName());
				this.onDestroy();
				this.finish();
			}
		}
	}
	protected void muteSystemAudio(){
		AudioManager amanager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
		amanager.setStreamMute(AudioManager.STREAM_SYSTEM, true);
	}
	protected void enableSystemAudio(){
		AudioManager amanager=(AudioManager)getSystemService(Context.AUDIO_SERVICE);
		amanager.setStreamMute(AudioManager.STREAM_SYSTEM, false);
	}
	private Intent createEmailOnlyChooserIntent(Intent source,
			CharSequence chooserTitle) {
		Stack<Intent> intents = new Stack<Intent>();
		Intent i = new Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto",
				"info@domain.com", null));
		List<ResolveInfo> activities = getPackageManager()
				.queryIntentActivities(i, 0);

		for (ResolveInfo ri : activities) {
			Intent target = new Intent(source);
			target.setPackage(ri.activityInfo.packageName);
			intents.add(target);
		}

		if (!intents.isEmpty()) {
			Intent chooserIntent = Intent.createChooser(intents.remove(0),
					chooserTitle);
			chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS,
					intents.toArray(new Parcelable[intents.size()]));

			return chooserIntent;
		} else {
			return Intent.createChooser(source, chooserTitle);
		}
	}
	
	private void emailContent() {
		Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("*/*");
        //i.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(crashLogFile));
        i.putExtra(Intent.EXTRA_EMAIL, new String[] {
            "gary@garyzhu.net"
        });
        i.putExtra(Intent.EXTRA_SUBJECT, "Dictation contents");
        
        i.putExtra(Intent.EXTRA_TEXT, "Some crash report details");

        result = (TextView)findViewById(R.id.comp_result);
		String resText = result.getText().toString();
        startActivity(createEmailOnlyChooserIntent(i, resText));
	}
}
