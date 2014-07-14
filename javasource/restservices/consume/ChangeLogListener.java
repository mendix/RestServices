package restservices.consume;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static restservices.RestServices.CHANGE_DATA;
import static restservices.RestServices.CHANGE_DELETED;
import static restservices.RestServices.CHANGE_KEY;
import static restservices.RestServices.CHANGE_SEQNR;
import static restservices.RestServices.PARAM_SINCE;
import static restservices.RestServices.PARAM_TIMEOUT;
import static restservices.RestServices.PATH_CHANGES;
import static restservices.RestServices.PATH_FEED;
import static restservices.RestServices.PATH_LIST;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.methods.GetMethod;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import restservices.RestServices;
import restservices.proxies.DataSyncState;
import restservices.proxies.TrackingState;
import restservices.util.JsonDeserializer;
import restservices.util.Utils;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableMap;
import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import communitycommons.XPath;

public class ChangeLogListener {

	private String url;
	private String onUpdateMF;
	private String onDeleteMF;
	private DataSyncState state;
	private static Map<String, ChangeLogListener> activeListeners = Collections.synchronizedMap(new HashMap<String, ChangeLogListener>());
	volatile boolean cancelled = false;
	private Map<String, String> headers;
	private long	timeout;
	private volatile GetMethod currentRequest;
	private Thread listenerThread;
	private volatile boolean isConnected = false;
	
	
	private ChangeLogListener(String collectionUrl, String onUpdateMF, String onDeleteMF, long timeout) throws Exception {
		checkNotNull(collectionUrl, "URL should not be null");
		checkArgument(isNotEmpty(onUpdateMF), "On update should be non empty");
		checkArgument(isNotEmpty(onDeleteMF), "On delete should be non empty");
		
		this.url = collectionUrl;
		this.onUpdateMF = onUpdateMF;
		this.onDeleteMF = onDeleteMF;
		this.timeout = timeout;
		this.state = XPath.create(Core.createSystemContext(), DataSyncState.class).findOrCreate(DataSyncState.MemberNames.CollectionUrl, url);
	}
	
	public ChangeLogListener follow() {
		synchronized (activeListeners) {
			if (activeListeners.containsKey(url))
				throw new IllegalStateException("Already listening to " + url);
		
			activeListeners.put(url, this);
		}
		
		
		headers = RestConsumer.nextHeaders.get();
		RestConsumer.nextHeaders.set(null);
		
		this.listenerThread = (new Thread() {
			
			private long nextRetryTime = 10000;
			@Override
			public void run() {
				while(!cancelled) {
					try {
						startConnection();
					}
					catch (Exception e)
					{
						RestServices.LOGCONSUME.error("Failed to setup follow stream for " + getChangesRequestUrl(true) + ", retrying in " + nextRetryTime + "ms: " + e.getMessage());//, e);
						try {
							Thread.sleep(nextRetryTime);
							if (nextRetryTime < 60*60*1000)
								nextRetryTime *= 1.3;
						} catch (InterruptedException e1) {
							cancelled = true;
						} //Retry each 10 seconds
					}
				}
			}
		});
		
		listenerThread.setName("REST consume thread " + url);
		listenerThread.start();
		return this;
	}

	void startConnection() throws IOException,
			HttpException {
		String requestUrl = getChangesRequestUrl(true);
		
		GetMethod get = this.currentRequest = new GetMethod(requestUrl);
		get.setRequestHeader(RestServices.HEADER_ACCEPT, RestServices.CONTENTTYPE_APPLICATIONJSON);
		
		RestConsumer.includeHeaders(get, headers);
		int status = RestConsumer.client.executeMethod(get);
		try {
			if (status != IMxRuntimeResponse.OK)
				throw new RuntimeException("Failed to setup stream to " + url +  ", status: " + status);

			InputStream inputStream = get.getResponseBodyAsStream();
		
			JSONTokener jt = new JSONTokener(inputStream);
			JSONObject instr = null;
			
			try {
				isConnected  = true;
				while(true) {
					instr = new JSONObject(jt);
					
					processChange(instr);
				}
			}
			catch(InterruptedException e2) {
				cancelled = true;
				RestServices.LOGCONSUME.warn("Changefeed interrupted", e2);
			}
			catch(Exception e) {
				//Not graceful disconnected?
				if (!cancelled && !(jt.end() && e instanceof JSONException))
					throw new RuntimeException(e);
			}
		}
		finally {
			isConnected = false;
			get.releaseConnection();
		}
	}

	public String getChangesRequestUrl(boolean useFeed) {
		return Utils.appendParamToUrl(Utils.appendParamToUrl(
			Utils.appendSlashToUrl(url) + PATH_CHANGES + "/" + (useFeed ? PATH_FEED : PATH_LIST),
			PARAM_SINCE, String.valueOf((long) state.getSequenceNr())),
			PARAM_TIMEOUT, String.valueOf(timeout));
	}

	void fetch() throws IOException, Exception {
		RestConsumer.readJsonObjectStream(getChangesRequestUrl(false), new Predicate<Object>() {

			@Override
			public boolean apply(Object data) {
				if (!(data instanceof JSONObject))
					throw new RuntimeException("Changefeed expected JSONObject, found " + data.getClass().getSimpleName());
				try {
					processChange((JSONObject) data);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				return true;
			}
			
		});
	}
	
	void processChange(JSONObject instr) throws Exception {
		IContext c = Core.createSystemContext();

		long revision = instr.getLong(CHANGE_SEQNR); 

		RestServices.LOGCONSUME.info("Receiving update for " + url + " #" + revision + " object: '" + instr.getString(CHANGE_KEY) + "'"); 
		
		if (instr.getBoolean(CHANGE_DELETED)) {
			Map<String, String> args = Utils.getArgumentTypes(onDeleteMF);
			if (args.size() != 1 || !"String".equals(args.values().iterator().next()))
				throw new RuntimeException(onDeleteMF + " should have one argument of type string");
			Core.execute(c, onDeleteMF, ImmutableMap.of(args.keySet().iterator().next(), (Object) instr.getString(CHANGE_KEY)));
		}
		else {
			IDataType type = Utils.getFirstArgumentType(onUpdateMF);
			if (!type.isMendixObject())
				throw new RuntimeException("First argument should be an Entity! " + onUpdateMF);

			IMendixObject target = Core.instantiate(c, type.getObjectType());
			JsonDeserializer.readJsonDataIntoMendixObject(c, instr.getJSONObject(CHANGE_DATA), target, true);
			Core.commit(c, target);
			Core.execute(c, onUpdateMF, ImmutableMap.of(Utils.getArgumentTypes(onUpdateMF).keySet().iterator().next(), (Object) target));
		}
		
		if (revision <= state.getSequenceNr()) 
			RestServices.LOGCONSUME.warn("Received revision (" + revision + ") is smaller than the latest known revision (" + state.getSequenceNr() +"), probably the collections are out of sync?");
		
		state.setSequenceNr(revision);
		state.commit();
	}
	
	private void close() {
		activeListeners.remove(url);
		cancelled = true;
		if (this.currentRequest != null)
			this.currentRequest.abort();
		else if (!this.listenerThread.isInterrupted()) //It might be waiting
			this.listenerThread.interrupt();
	}

	public static synchronized void follow(final String collectionUrl, final String updateMicroflow,
			final String deleteMicroflow, final long timeout) throws HttpException, IOException, Exception {
		new ChangeLogListener(collectionUrl, updateMicroflow, deleteMicroflow, timeout).follow();
	}
	
	public static synchronized void unfollow(String collectionUrl) {
		if (activeListeners.containsKey(collectionUrl))
			activeListeners.get(collectionUrl).close();
	}
	
	public static synchronized void fetch(String collectionUrl, String updateMicroflow, String deleteMicroflow) throws Exception {
		new ChangeLogListener(collectionUrl, updateMicroflow, deleteMicroflow, 0L).fetch();
	}

	public static void resetDataSyncState(String collectionUrl) throws CoreException {
		if (activeListeners.containsKey(collectionUrl))
			throw new IllegalStateException("Cannot reset state for collection '" + collectionUrl + "', there is an active listener. Please unfollow first");
		XPath.create(Core.createSystemContext(), DataSyncState.class).eq(DataSyncState.MemberNames.CollectionUrl, collectionUrl).deleteAll();
	}

	public static TrackingState getTrackingState(String collectionUrl) {
		ChangeLogListener feed = activeListeners.get(collectionUrl);
		if (feed == null)
			return TrackingState.Paused;
		if (!feed.cancelled && feed.isConnected)
			return TrackingState.Tracking;
		return TrackingState.Connecting;
	}
}
