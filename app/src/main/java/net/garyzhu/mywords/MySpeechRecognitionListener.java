package net.garyzhu.mywords;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

import android.annotation.TargetApi;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

/**
 * a speech recognition class
 * 
 * @author gzhu
 */
class MySpeechRecognitionListener implements RecognitionListener {

	public static int COMMAND_I_AM_DONE = 100;
	public static int COMMAND_READ_TO_ME = 101;
	public static int COMMAND_NONE = 102;
	
	private DictateActivity _da = null;
	
	MySpeechRecognitionListener(DictateActivity da) {
		_da = da;
	}
	
	@Override
	public void onBeginningOfSpeech() {
		_da.myLog("Speech", "onBeginningOfSpeech");
	}

	@Override
	public void onBufferReceived(byte[] buffer) {
		_da.myLog("Speech", "onBufferReceived");
	}

	@Override
	public void onEndOfSpeech() {
		_da.myLog("Speech", "onEndOfSpeech");
	}

	@Override
	public void onError(int error) {
		String errMsg = "";
		switch(error) {
		case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
			_da.myError("Speech", "speech timeout");
			_da.setSpeakPrompt(true);
			_da.sendMessage("Can not hear you, try again!");
			return;
		case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
			errMsg = " recognizer busy.";
			_da.sr.cancel();
			break;
		case SpeechRecognizer.ERROR_NO_MATCH:
			errMsg = " no match, not English?";
			break;
		}

		_da.myError("Speech", "onError " + error + errMsg);
		_da.setSpeakPrompt(true);
		_da.sendMessage("There are some errors! " + errMsg +" Try again.");
	}

	@Override
	public void onEvent(int eventType, Bundle params) {
		_da.myLog("Speech", "onEvent");
	}

	@Override
	public void onPartialResults(Bundle partialResults) {
		_da.myLog("Speech", "onPartialResults");
	}

	@Override
	public void onReadyForSpeech(Bundle params) {
		_da.myLog("Speech", "onReadyForSpeech");
	}

	@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
	@Override
	public void onResults(Bundle results) {
		_da.myLog("Speech", "onResults");
		ArrayList<String> matches = results
				.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
		float[] scores = results
				.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);

		int total = matches.size();
		if (total == 0) {
			_da.sendMessage("Cannot hear you, try again.");
		} else {
			if (_da.isExpectingCommand()) {
				// processing command
				int c = isCommand(matches);
				if (c > 0) {
					_da.myLog("onRecog", "recognized as command " + c);
					_da.setExpectingCommand(false);
					_da.sendMessage("choice " + c);
				} else {
					String heardThis = matches.get(0);
					if (matches.size() > 1) {
						heardThis += " or maybe " + matches.get(1);
					}
					_da.myLog("onRecog", "none command: " + heardThis);
					String numChoice ;
					int x  = _da.getChoiceLength() - 1;
					if (x == 1) {
						numChoice = " 1 ";
					} else {
						numChoice = " 1 to " + x + " ";
					}
					_da.setSpeakPrompt(true);
					_da.sendMessage("I heard you say " + heardThis +"; Not a valid command, please say a number: " + numChoice + ", or none of the above");
				}
			} else {
				// processing sentence 
				int incr = 0;
				for (String oneMatch : matches) {
					float score = scores[incr];
					NumberFormat nf = NumberFormat.getPercentInstance();
					nf.setMaximumFractionDigits(0);
	
					incr++;
					// speak normal
					_da.addSpeech("0 Choice " + incr + " with confidence level "
							+ nf.format(score));
					// emphasis speak
					_da.addSpeech("1 " + oneMatch);
	
					_da.addChoice("Choice " + incr + " (" + nf.format(score)
							+ "): " + oneMatch);
				}
				_da.addChoice("  None of the above");
				String numChoice ;
				if (matches.size() == 1) {
					numChoice = " 1 ";
				} else {
					numChoice = " 1 to " + matches.size() + " ";
				}
				_da.addSpeech("P  please say a number: " + numChoice + ", or none of the above");
				
				_da.setExpectingCommand(true);
				_da.speakOut("Please select below " + total + " choices.", TextToSpeech.QUEUE_FLUSH);
			}
		}
	}

	@Override
	public void onRmsChanged(float rmsdB) {
		// myLog("Speech", "onRmsChanged");
	}
	
	/**
	 * this method will go through possible choices to decide whether the person
	 * said one of the 3 commands "I am done", "Read to me" or "None";
	 * or the numbers as 1, 2, 3, 4, 5 for choices, return this int value if it is a command;
	 * otherwise, return 0.
	 * 
	 * @param matches  a list of possible choices
	 * @return    integer number user chose, 0 means none of them are chosen
	 */
	private int isCommand(List<String> matches) {
		String dones[] = {"i am done", "am done", "i am none"};
		String nones[] = {"none", "learn", "none of above", "none of about", "none of the above"};
		String reads[] = {"read to me"};
		String ones[] = {"one", "1", "won"};
		String twos[] = {"two", "2", "too", "tool", "to"};
		String threes[] = {"3", "three", "free", "tree"};
		String fours[] = {"4", "for", "four", "foo"};
		String fives[] = {"5", "five"};
		
		if (findMatch(dones, matches)) {
			return COMMAND_I_AM_DONE;
		} else if (findMatch(nones, matches)) {
			return COMMAND_NONE;
		} else if (findMatch(reads, matches)) {
			return COMMAND_READ_TO_ME;
		} else if (findMatch(ones, matches)) {
			return 1;
		} else if (findMatch(twos, matches)) {
			return 2;
		} else if (findMatch(threes, matches)) {
			return 3;
		} else if (findMatch(fours, matches)) {
			return 4;
		} else if (findMatch(fives, matches)) {
			return 5;
		} 
				
		return 0;
	}
	
	private boolean findMatch(String x[], List<String> m) {
		for (String sample: x) {
			for (String r: m) {
				if (sample.equalsIgnoreCase(r)) {
					return true;
				}
			}
		}
		return false;
	}
} // end of class MySpeechRecognitionListing
	