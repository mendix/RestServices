package restservices;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONObject;

import restservices.consume.RestConsumer;
import restservices.consume.ObjectCache;
import restservices.proxies.GetResult;
import restservices.proxies.RestObject;
import restservices.publish.PublishedService;
import restservices.util.Utils;

import com.mendix.core.Core;
import com.mendix.m2ee.api.IMxRuntimeResponse;
import com.mendix.m2ee.log.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

public class RestServices {
	
	static Pattern keyPattern = Pattern.compile("^[-a-zA-Z0-9_~@^*:;,.]+$"); //anything that doesnt need special url parsing goes..

	public static final ILogNode LOG = Core.getLogger("RestPublisher");
	
	public static final String VERSION = "1.0.0";
	
	static Map<String, PublishedService> services = new HashMap<String, PublishedService>();
	static Map<String, PublishedService> servicesByEntity = new HashMap<String, PublishedService>();
	
	public static boolean isValidKey(String key) {
		if (key == null)
			return false;
		return keyPattern.matcher(key).matches();
	}
	
	public static PublishedService getServiceForEntity(String entityType) {
		if (servicesByEntity.containsKey(entityType))
			return servicesByEntity.get(entityType);
		
		//if not look into super entitites as well!
		IMetaObject meta = Core.getMetaObject(entityType);
		if (meta.getSuperObject() != null) {
			PublishedService superService = getServiceForEntity(meta.getSuperName());
			if (superService != null) {
				servicesByEntity.put(entityType, superService);
				return superService;
			}
		}
		servicesByEntity.put(entityType, null); //no service. Remember that
		return null;
	}



	public static void getCollection(IContext context, String collectionUrl, List<IMendixObject> resultList, IMendixObject firstResult) throws Exception
	{
		if (resultList.size() > 0)
			throw new RuntimeException("Expected stub collection to have size 0");
		
		Pair<Integer, String> result = RestConsumer.retrieveJsonUrl(collectionUrl, null);
		if (result.getLeft() != IMxRuntimeResponse.OK)
			throw new RuntimeException("Expected status OK on " + collectionUrl);
		
		ObjectCache cache = new ObjectCache();
		JSONArray ar = new JSONArray(result.getRight()); //TODO: stream would be faster!
		
		for(int i = 0; i < ar.length(); i++) {
			IMendixObject item;
			
			if (i == 0)
				item = firstResult;
			else
				item = Core.instantiate(context, firstResult.getType());
			
			RestConsumer.readJsonObjectIntoMendixObject(context, ar.getJSONObject(i), item, cache);
			resultList.add(item);
		}
	}
	
	public static GetResult getObject(IContext context, String url, IMendixObject target) throws Exception {
		return getObject(context, url, target, new ObjectCache());
	}
	
	public static GetResult getObject(IContext context, String url, IMendixObject target, ObjectCache cache) throws Exception {
		//pre
		if (context == null)
			throw new IllegalArgumentException("Context should not be null");
		if (target == null)
			throw new IllegalArgumentException("Target should not be null");
		
		if (!Utils.isUrl(url))
			throw new IllegalArgumentException("Target should have a valid URL attribute");
		
		String etag = null;
		//analyze input
		if (Core.isSubClassOf(RestObject.entityName, target.getType())) {
			String key = Utils.getKeyFromUrl(url);
			target.setValue(context, RestServices.ID_ATTR, key);
			etag = target.getValue(context, RestServices.ETAG_ATTR);
		}

		//fetch
		Pair<Integer, String> result = RestConsumer.retrieveJsonUrl(url, etag);
		
		if (result.getLeft() == IMxRuntimeResponse.NOT_MODIFIED)
			return GetResult.NotModified;
		else if (result.getLeft() == IMxRuntimeResponse.NOT_FOUND)
			return GetResult.NotFound;
		
		//parse
		cache.putObject(url, target);
		RestConsumer.readJsonObjectIntoMendixObject(context, new JSONObject(result.getRight()), target, cache);

		return GetResult.OK;
	}

	public static final String UTF8 = "UTF-8";

	public static final String URL_ATTR = "_url";

	public static final String TEXTJSON = "text/json";

	public static final String STYLESHEET = 
	"body { font-family: Arial; font-size: 0.8em; padding: 20px 60px; } " +
	"td {padding: 5px 10px; border: 1px solid #e2e2e2; } " +
	"tr:nth-child(odd) {background-color: #e2e2e2; } " +
	"h1 {color: navy; }" +
	"hr {border-style:none; border-bottom: 1px dotted #aaa;}";

	public static final int MAXPOLLQUEUE_LENGTH = 10000;

	public static final int LONGPOLL_MAXDURATION = 60; //In seconds

	public static final String IFNONEMATCH_HEADER = "If-None-Match";

	public static final String ID_ATTR = "_id"; //TODO: is _id really needed?

	public final static String HANDLERPATH = "rest/";

	public static final String ETAG_HEADER = "ETag";

	public static final String ETAG_ATTR = "_etag";

	public static final String CONTENTTYPE_PARAM = "contenttype";

	public static final String ACCEPT_HEADER = "Accept";

	public static void registerService(String name, PublishedService def) {
		services.put(name,  def);
		if (RestServices.servicesByEntity.containsKey(def.getSourceEntity()))
			throw new RuntimeException(String.format("Invalid service definition in '%s': Another services for entity '%s' is already defined", name, def.getSourceEntity()));
		RestServices.servicesByEntity.put(def.getSourceEntity(), def);
	}

	public static PublishedService getService(String name) {
		return services.get(name);
	}

	public static Set<String> getServiceNames() {
		return services.keySet();
	}
}
