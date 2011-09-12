// This code is licensed under the Apache License, Version 2.0 
// http://www.apache.org/licenses/LICENSE-2.0.html

package com.axosoft.util;

import java.util.ArrayList;
import java.util.EventListener;
import java.util.List;
import java.util.concurrent.Semaphore;

import android.content.Context;
import android.util.Log;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * This class helps with and tries to simplify setting up and listening to a Google App Engine channel on an Android
 * device (Reference - http://code.google.com/appengine/docs/java/channel/).
 * 
 * The ChannelAPI class requires 4 things to function:
 * 
 * 		- A path to the Google App Engine. This is usually held on your GAE server at "_ah/channel/jsapi" 
 * 		(e.x. http://my-gae-server.appspot.com/_ah/channel/jsapi)
 * 
 * 		- A token from the server that was used to create the channel. This will need to be set up on the server
 * 		and passed into the ChannelAPI object before a channel can be established and messages from the server
 * 		can be received.
 * 
 * 		- An object that listens to events that occur on the channel. This can be done by implementing the
 * 		ChannelAPIEventListener in the class.
 * 
 * 		- An Android Context that the class will be initialized under. This is used to properly initialize the WebView object.
 * 
 * When the ChannelAPI has been properly instantiated and a channel has been established with the server the
 * listener(s) will start to receive events that occur on the channel, namely channel open, channel closed, 
 * message, error and channel ready state updates. From here you can handle any message sent back from the server
 * via the channel.
 * 
 * NOTE:	A channel is a ONE-WAY route of communication from the server to the device. To send messages to the server
 * 			they still have to be sent as HTTP/HTTPS requests to HttpServlets on the GAE server. 
 */
public class ChannelAPI {
	
	// This is the code for the Javascript engine required to communicate with the channel connected to the server.
	public static final String CHANNEL_API_JAVASCRIPT_START = "<html><head></head><body><script type=\"text/javascript\" src=\"";
	public static final String CHANNEL_API_JAVASCRIPT_END	= "\"></script><script language=\"javascript\">function closeConnection(){if(socket){socket.close()}}function connectToServer(a){channel=new goog.appengine.Channel(a);socket=channel.open();socket.onopen=onOpen;socket.onmessage=onMessage;socket.onerror=onError;socket.onClose=onClose}function onError(a){window.channelAPI.channelError(a.code,a.description)}function onMessage(a){window.channelAPI.channelMessageReceived(a.data)}function onClose(){window.channelAPI.channelClosed()}function onOpen(){window.channelAPI.channelOpen()}var channel;var socket;window.onload=function(){window.channelAPI.pageLoaded()}</script></body></html>";
	
	// How long a token is valid for.
	// Reference: http://code.google.com/appengine/docs/java/channel/overview.html#Tokens_and_Security
	public static final long TOKEN_VALID_LENGTH_MILLIS = 7200000; // 2 hours
	
	// The tag used for anything output to the LogCat on Android.
	private static final String LOG_TAG = "ChannelAPI";
	
	// This runs the Javascript engine.
	private WebView mWebView;
	
	// The token we use to create the channel. Anyone with this token can listen in on the channel so 
	// it should be treated as a secret.
	private String mToken;
	
	// This is when the object received the token. Every 2 hours the token must be refreshed and this is
	// what's used to reference if the token needs to be refreshed or not
	private long mTimeTokenWasRecievedMillis;
	
	// Class that contains callback functions that the Javascript engine calls to let any listeners know what's
	// going on with respect to the channel.
	private ChannelAPIJavaScriptInterface mJsInterface;
	
	// This lets the ChannelAPI class know that the Javascript engine is done loading so we can start using it.
	private Semaphore mJSEngineLock;
	
	// If the channel has been closed this will be set to true
	private boolean mChannelClosed;
	
	// If the object is outputting logging information to the Android LogCat this will be true.
	private boolean mLogging;
	
	/**
	 * Builds the object and sets up the background WebView object loaded with the necessary javascript to
	 * listen to the channel.
	 * @param context The Android context of the program. This is needed to instantiate the WebView
	 * @param gaeChannelApiSource This is the path to the Channel API Javascript source on your GAE server.
	 * (e.g. http://myGaeApp.appspot.com/_ah/channel/jsapi)
	 */
	public ChannelAPI(Context context, String gaeChannelApiSource) {
		mWebView 		= new WebView(context);
		mJsInterface 	= new ChannelAPIJavaScriptInterface();
		mChannelClosed 	= true;
		mLogging 		= false;
		
		WebSettings webSettings = mWebView.getSettings();
		webSettings.setSavePassword(false);
		webSettings.setSaveFormData(false);
		webSettings.setSupportZoom(false);
		webSettings.setJavaScriptEnabled(true);
		
		mWebView.setWebChromeClient(new ChannelAPIWebChromeClient());
		mWebView.addJavascriptInterface(mJsInterface, "channelAPI");
		mWebView.loadDataWithBaseURL(null, CHANNEL_API_JAVASCRIPT_START + gaeChannelApiSource + CHANNEL_API_JAVASCRIPT_END, "text/html", "utf-8", null);
		
		// The Javascript engine is now loading so set the semaphore up so that we can't call it until we're ready.
		mJSEngineLock = new Semaphore(0);
	}
	
	/**
	 * Builds the object, sets up the background WebView object loaded with the necessary javascript to
	 * listen to the channel, sets the token we'll use to instantiate the channel and attempts to connect to
	 * the channel. 
	 * @param context The Android context of the program. This is needed to instantiate the WebView
	 * @param gaeChannelApiSource This is the path to the Channel API Javascript source on your GAE server.
	 * (e.g. http://myGaeApp.appspot.com/_ah/channel/jsapi)
	 * @param token The token received from the server that ChannelAPI will use to open the channel
	 * @param listener The object that will listen to all of the events that happen on the channel. 
	 */
	public ChannelAPI(Context context, String gaeChannelApiSource, String token, ChannelAPIEventListener listener) {
		this(context, gaeChannelApiSource);
		setToken(token);
		addListener(listener);
		connect();
	}

	/**
	 * Asynchronously attempts to establish a connection on the channel to the server. 
	 * @return Will return false if the token used to establish the channel is invalid or if there
	 * are no ChannelAPIEventListeners.
	 */
	public boolean connect() {
		if(!isTokenValid() || mJsInterface.mListeners.size() < 1) {
			return false;
		}
		
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					mJSEngineLock.acquire();
					
					if(mToken == null || mToken.equals("")) {
						if(mLogging) Log.e(LOG_TAG, "mToken is empty or null in ChannelAPI when attempting to create a new channel");
					} else {
						mWebView.loadUrl("javascript:connectToServer('" + mToken + "');");
					}
					
					mJSEngineLock.release();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}).start();
		
		return true;
	}
	
	/**
	 * Closes the connection on the channel.
	 */
	public void close() {
		mWebView.loadUrl("javascript:closeConnection()");
	}
	
	/**
	 * Checks to see if the token used to establish the channel connection is still good.
	 * @return True if token hasn't expired yet, isn't null and isn't empty. False otherwise.
	 */
	public boolean isTokenValid() {
		return
			mToken != null && !mToken.equals("") &&
			System.currentTimeMillis() < mTimeTokenWasRecievedMillis + TOKEN_VALID_LENGTH_MILLIS;
	}
	
	/**
	 * Sets the token used to establish the channel connection with the server.
	 * @param token The token to use.
	 * @return False if the token is null or empty
	 */
	public boolean setToken(String token) {
		boolean result;
		
		if(token != null && !token.equals("")) {
			mToken = token;
			mTimeTokenWasRecievedMillis = System.currentTimeMillis();
			result = true;
		} else {
			if(mLogging) Log.e(LOG_TAG, "token cannot be null or empty");
			result = false;
		}
		
		return result;
	}
	
	/**
	 * Adds a ChannelAPIEventListener to the Javascript engine interface. 
	 * @param listener The listener to add.
	 */
	public synchronized void addListener(ChannelAPIEventListener listener) {
		if(!mJsInterface.mListeners.contains(listener)) {
			mJsInterface.mListeners.add(listener);
		}
	}
	
	/**
	 * Removes a ChannelAPIEventListener to the Javascript engine interface.
	 * @param listener The listener to remove.
	 */
	public synchronized void removeListener(ChannelAPIEventListener listener) {
		mJsInterface.mListeners.remove(listener);
	}
	
	/**
	 * Removes all ChannelAPIEventListeners from  the Javascript engine interface.
	 */
	public synchronized void removeAllListeners() {
		mJsInterface.mListeners.clear();
	}
	
	/**
	 * This method both returns the current closed status of the channel and also polls the
	 * javascript to see if the channel there has changed since the last time we received an
	 * update about the closed status of the channel.
	 * 
	 * After the javascript engine has finished updating the the channel ready state status a
	 * channelReadyStateUpdated is fired and contains the fresh status.
	 * @return False if the channel has been closed since the last update.
	 */
	public boolean isChannelClosed() {
		return mChannelClosed;
	}
	
	/**
	 * Sets whether or not logging is enabled.
	 * @param logging True if outputting to the log, false if not.
	 */
	public void setLogging(boolean logging) {
		mLogging = true;
	}
	
	/**
	 * Returns whether or not logging is enabled
	 * @return True if outputting to the log, false if not.
	 */
	public boolean isLogging() {
		return mLogging;
	}
	
	// -----------------------------------------------------------------------
	// --------------------- JAVASCRIPT INTERFACE CODE -----------------------
	// -----------------------------------------------------------------------
	
	/**
	 * This class just hooks into all of the "alert()" methods that could be called from the Javascript engine
	 * and repeats them out to Android's LogCat.
	 */
	private final class ChannelAPIWebChromeClient extends WebChromeClient {
		@Override
		public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
			if(mLogging) Log.d(LOG_TAG, "Javascript Alert: " + message);
			result.confirm();
			return true;
		}
	}
	
	/**
	 * This class is used as a bunch of callback methods for the Javascript engine so that we can receive data
	 * from the channel and messages from the server.
	 */
	private final class ChannelAPIJavaScriptInterface {
		private List<ChannelAPIEventListener> mListeners;
		
		public ChannelAPIJavaScriptInterface() {
			mListeners = new ArrayList<ChannelAPIEventListener>();
		}
		
		// NOTE: These classes are called from the Javascript engine.
		// The warnings can be ignored...
		
		/**
		 * This is called when the Javascript engine has been loaded and is ready to start receiving commands
		 */
		@SuppressWarnings("unused")
		public void pageLoaded() {
			mJSEngineLock.release();
		}
		
		/**
		 * This is called when the channel has been opened in the Javascript engine.
		 */
		@SuppressWarnings("unused")
		public void channelOpen() {
			if(mLogging) Log.i(LOG_TAG, "Channel open");
			mChannelClosed = false;
			
			for(ChannelAPIEventListener listener : mListeners) {
				listener.onChannelAPIOpen();
			}
		}
		
		/**
		 * This is called when the channel has been closed in the Javascript engine.
		 */
		@SuppressWarnings("unused")
		public void channelClosed() {
			if(mLogging) Log.i(LOG_TAG, "Channel closed");
			mChannelClosed = true;
			
			for(ChannelAPIEventListener listener : mListeners) {
				listener.onChannelAPIClosed();
			}
		}
		
		/**
		 * This is called whenever a message is received from the server. It passes the string
		 * message back through the onChannelAPIUpdateEvent.
		 * @param message The message received from the server.
		 */
		@SuppressWarnings("unused")
		public synchronized void channelMessageReceived(String message) {
			if(mLogging) Log.i(LOG_TAG, "Update received on channel");
			
			// Fire the event
			for(ChannelAPIEventListener listener : mListeners) {
				listener.onChannelAPIUpdateEvent(message);
			}
		}
		
		/**
		 * If there's an error on the channel this method is called. It will have the error code
		 * (an HTTP code) and a short description of the error. Some errors do not have a description
		 * sent back but just the error code by itself.
		 * @param code The HTTP error code of the error that occurred. 
		 * @param description The description of the error (can be blank).
		 */
		@SuppressWarnings("unused")
		public void channelError(String code, String description) {
			if(mLogging) Log.e(LOG_TAG, "Channel Error Code: " + code + ", Channel Error Description: " + description);

			for(ChannelAPIEventListener listener : mListeners) {
				listener.onChannelAPIError(code, description);
			}
		}
	}
	
	// -----------------------------------------------------------------------
	// --------------------------- EVENT CODE --------------------------------
	// -----------------------------------------------------------------------
	
	/**
	 * The listener interface that needs to be implemented for an object to receive channel messages.
	 */
	public interface ChannelAPIEventListener extends EventListener {
		/**
		 * This should handle the logic for when the channel is opened.
		 */
		public void onChannelAPIOpen();
		
		/**
		 * This should handle the logic for when the channel is closed. This is NOT guaranteed to be called
		 * if the channel is closed due to an error.
		 */
		public void onChannelAPIClosed();
		
		/**
		 * When messages are received from the server they are sent back through this event.
		 * @param message The message that was sent from the server. 
		 */
		public void onChannelAPIUpdateEvent(String message);
		
		/**
		 * If an error occurs on the channel then this event will be fired and then can be handled in the listener
		 * class. 
		 */
		public void onChannelAPIError(String code, String description);
	}
}
