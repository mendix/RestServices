package restservices;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import restservices.publish.DataService;
import restservices.publish.RestServiceHandler;
import restservices.util.Utils;

import com.mendix.core.Core;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;
import communitycommons.XPath;

public class RestServices {
	
	/**
	 * Version of the RestServices module
	 */
	public static final String VERSION = "2.1.3";

	/**
	 * Amount of objects that are processed by the module at the same time.
	 * Larger number yields better performance but increases memory consumptions. 
	 * 
	 * Defaults to 1000.
	 */
	public static int BATCHSIZE = 1000;
	public static final int MAXPOLLQUEUE_LENGTH = 10000;
	public static final int LONGPOLL_MAXDURATION = 50; //In seconds

	public static ILogNode LOGPUBLISH = Core.getLogger("RestPublish");
	public static ILogNode LOGCONSUME = Core.getLogger("RestConsume");
	public static ILogNode LOGUTIL = Core.getLogger("RestUtil");
	
	public static final String END_OF_HTTPHEADER = "\r\n\r\n";
	public static final String UTF8 = "UTF-8";
	public static final String BASIC_AUTHENTICATION = "Basic";
	public static final String CURRENTUSER_TOKEN = "'" + XPath.CurrentUser + "'";
	public static final String STYLESHEET =
			"body { font-family: Arial; font-size: 0.8em; padding: 0px 60px; margin: 0px; }"+
			"h1 { margin: 0 -60px 20px; background-color: #5c3566; font-size: em; padding: 20px 60px; color: white; box-shadow: 3px 3px 2px #666;text-transform:uppercase }"+
			"table { border-spacing: 0px; margin-top:-4px } "+
			"td:first-child { border-right: 1px dotted #ccc; font-weight: bold; text-align: right; color: #666;font-size:0.8em;padding-top:6px}"+
			"td:first-child, th:first-child { background-color: #f9f9f9; }"+
			"td { padding: 4px 8px; vertical-align: top; min-width: 100px; }"+
			"a, a:active, a:visited { color: #5c3566 }"+
			"h2 { margin-top: 40px; color: white; background-color:#333; border-radius:2px; padding: 8px 16px;}"+
			"h2 small { font-size: 0.5em; } h2 a { color: white !important; text-decoration: none; }";

	public static final String CONTENTTYPE_APPLICATIONJSON = "application/json";
	public static final String CONTENTTYPE_FORMENCODED = "application/x-www-form-urlencoded";
	public static final String CONTENTTYPE_MULTIPART = "multipart/form-data";
	public static final String CONTENTTYPE_OCTET = "application/octet-stream";

	public static final String HEADER_ETAG = "ETag";
	public static final String HEADER_IFNONEMATCH = "If-None-Match";
	public static final String HEADER_ACCEPT = "Accept";
	public static final String HEADER_AUTHORIZATION = "Authorization";
	public static final String HEADER_CONTENTTYPE = "Content-Type";
	public static final String HEADER_WWWAUTHENTICATE = "WWW-Authenticate";
	public static final String HEADER_CONTENTDISPOSITION = "Content-Disposition";
	
	public static String PATH_REST = "rest/";
	public static final String PATH_LIST = "list";
	public static final String PATH_FEED = "feed";
	public static final String PATH_CHANGES = "changes";
	
	public static final String PARAM_CONTENTTYPE = "contenttype";
	public static final String PARAM_SINCE = "since";
	public static final String PARAM_TIMEOUT = "timeout";
	public static final String PARAM_ABOUT = "about";
	public static final String PARAM_COUNT = "count" ;
	public static final String PARAM_DATA = "data";
	public static final String PARAM_OFFSET = "offset"; 
	public static final String PARAM_LIMIT = "limit"; 

	public static final String CHANGE_DATA = "data";
	public static final String CHANGE_KEY = "key";
	public static final String CHANGE_DELETED = "deleted";
	public static final String CHANGE_SEQNR = "seq";
	public static final String CHANGE_ETAG = "etag";
	public static final String CHANGE_URL = "url";


	static Map<String, DataService> servicesByEntity = new ConcurrentHashMap<String, DataService>();
	static Set<String> entitiesWithoutService = Collections.synchronizedSet(new HashSet<String>());
	
	public static DataService getServiceForEntity(String entityType) {
		if (servicesByEntity.containsKey(entityType)) {
			return servicesByEntity.get(entityType);
		} else if (entitiesWithoutService.contains(entityType)) {
			return null;
		} else {
			//if not look into super entitites as well!
			IMetaObject meta = Core.getMetaObject(entityType);
			if (meta.getSuperObject() != null) {
				DataService superService = getServiceForEntity(meta.getSuperName());
				if (superService != null) {
					servicesByEntity.put(entityType, superService);
					return superService;
				}
			}
			entitiesWithoutService.add(entityType); //no service. Remember that
			return null;
		}
	}

	public static String getBaseUrl() {
		return Utils.appendSlashToUrl(Core.getConfiguration().getApplicationRootUrl()) + PATH_REST;
	}

	/**
	 * For unit testing only!
	 */
	public static void clearServices() {
		RestServiceHandler.clearServices();
		servicesByEntity.clear();
	}

	public static void registerServiceByEntity(String sourceEntity,
			DataService def) {
		servicesByEntity.put(sourceEntity, def);
	}

	public static String getAbsoluteUrl(String relativeUrl) {
		return getBaseUrl() + Utils.removeLeadingSlash(Utils.appendSlashToUrl(relativeUrl));
	}

	public static void unregisterServiceByEntity(String sourceEntity,
			DataService dataService) {
		if (servicesByEntity.containsKey(sourceEntity) && servicesByEntity.get(sourceEntity) == dataService) {
			servicesByEntity.remove(sourceEntity);
		}
	}
}
