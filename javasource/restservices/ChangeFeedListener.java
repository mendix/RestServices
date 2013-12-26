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

import restservices.proxies.FollowChangesState;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import communitycommons.XPath;

public class ChangeFeedListener {
	private InputStream inputStream; //TODO: close somewhere
	private String url;
	private String onUpdateMF;
	private String onDeleteMF;
	private FollowChangesState state;
	private static Map<String, ChangeFeedListener> activeListeners = Collections.synchronizedMap(new HashMap<String, ChangeFeedListener>());

	private ChangeFeedListener(String collectionUrl, String onUpdateMF, String onDeleteMF) throws Exception {
		this.url = collectionUrl;
		this.onUpdateMF = onUpdateMF;
		this.onDeleteMF = onDeleteMF;
		
		if (activeListeners.containsKey(url))
			throw new IllegalStateException("Already listening to " + url);
		
		activeListeners.put(url, this);
		this.state = XPath.create(Core.createSystemContext(), FollowChangesState.class).findOrCreate(FollowChangesState.MemberNames.CollectionUrl, url);
		
		restartConnection();
	}

	private void restartConnection() throws IOException,
			HttpException {
		String requestUrl = url + "?feed=true&since=" + state.getRevision() + 1; //revision expresses the last *received* revision, so start +1. 
		
		GetMethod get = new GetMethod(requestUrl);
		get.setRequestHeader(Constants.ACCEPT_HEADER, Constants.TEXTJSON);
		
		int status = Consumer.client.executeMethod(get);
		if (status != IMxRuntimeResponse.OK)
			throw new RuntimeException("Failed to setup stream to " + url +  ", status: " + status);
		
		this.inputStream = get.getResponseBodyAsStream();

		(new Thread() {
			@Override
			public void run() {
				try {
					listen();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		}).start();
	}

	void listen()
			throws CoreException, Exception {
		JSONTokener jt = new JSONTokener(inputStream);
		JSONObject instr = null;
		
		boolean graceFullDisconnected = true;
		
		try {
			while(true) {
				instr = new JSONObject(jt);
				
				IContext c = Core.createSystemContext();
				//TODO: use constants
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
				
				long revision = instr.getLong("rev"); //TODO: doublecheck this context remains valid..
				
				if (revision <= state.getRevision()) 
					RestServices.LOG.warn("Received revision (" + revision + ") is smaller as latest known revision (" + state.getRevision() +"), probably the collections are out of sync?");
				
				state.setRevision(revision);
				state.commit();
			}
		}
		catch(Exception e) {
			graceFullDisconnected = false; //TODO unless..
		}
		
		if (graceFullDisconnected)
			restartConnection();
	}
	
	private void close() throws IOException {
		activeListeners.remove(url);
		this.inputStream.close();
	}

	public static synchronized ChangeFeedListener follow(String collectionUrl, String updateMicroflow,
			String deleteMicroflow) throws Exception {
		return new ChangeFeedListener(collectionUrl, updateMicroflow, deleteMicroflow);		
	}
	
	public static synchronized void unfollow(String collectionUrl) throws IOException {
		if (activeListeners.containsKey(collectionUrl))
			activeListeners.remove(collectionUrl).close();
	}
}
