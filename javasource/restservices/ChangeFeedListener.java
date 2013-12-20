package restservices;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.methods.GetMethod;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixObject;

public class ChangeFeedListener {
	private InputStream inputStream; //TODO: close somewhere
	private String url;
	private String onUpdateMF;
	private String onDeleteMF;
	private static Map<String, ChangeFeedListener> activeListeners = Collections.synchronizedMap(new HashMap<String, ChangeFeedListener>());

	private ChangeFeedListener(String collectionUrl, String onUpdateMF, String onDeleteMF) throws Exception {
		this.url = collectionUrl;
		this.onUpdateMF = onUpdateMF;
		this.onDeleteMF = onDeleteMF;
		
		synchronized (activeListeners) {
			if (activeListeners.containsKey(url))
				throw new IllegalStateException("Already listening to " + url);
			activeListeners.put(url, this);
		}

		GetMethod get = new GetMethod(collectionUrl);
		get.setRequestHeader(Constants.ACCEPT_HEADER, Constants.TEXTJSON);
		
		int status = Consumer.client.executeMethod(get);
		if (status != IMxRuntimeResponse.OK)
			throw new RuntimeException("Failed to setup stream to " + collectionUrl +  ", status: " + status);
		
		this.inputStream = get.getResponseBodyAsStream();

		(new Thread() {
			public void run() {
				try {
					listen();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		}).start();
	}

	private void listen()
			throws CoreException, Exception {
		JSONTokener jt = new JSONTokener(inputStream);
		JSONObject instr;
		while(null != (instr = new JSONObject(jt))) {
			IContext c = Core.createSystemContext();
			
			//TODO: store revision

			if (instr.getBoolean("deleted")) {
				Core.execute(c, onDeleteMF, instr.getString("key"));
			}
			else {
				IDataType type = Utils.getFirstArgumentType(onUpdateMF);
				if (!type.isMendixObject())
					throw new RuntimeException("First argument should be an Entity! " + onUpdateMF);

				IMendixObject target = Core.instantiate(c, type.getObjectType());
				Consumer.readJsonObjectIntoMendixObject(c, instr.getJSONObject("data"), target, new ObjectCache());
				Core.commit(c, target);
				Core.execute(c, onUpdateMF, target);
			}
		}
	}
	
	public synchronized void close() throws IOException {
		activeListeners.remove(url);
		this.inputStream.close();
	}
}
