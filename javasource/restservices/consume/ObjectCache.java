package restservices.consume;

import java.util.HashMap;
import java.util.Map;

import restservices.RestServices;
import restservices.proxies.RestObject;
import restservices.proxies.RestReference;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

public class ObjectCache { //TODO: make class private?
	//TODO: just one map with objecttype#url -> mendixobject
	private Map<String, IMendixObject> restObjects = new HashMap<String, IMendixObject>();
	private Map<String, IMendixObject> restReferences = new HashMap<String, IMendixObject>();
	
	
	public IMendixObject getObject(IContext context, String url,
			String otherSideType) throws Exception {

		//Return as reference?
		if (Core.isSubClassOf(RestReference.getType(), otherSideType)) {
			IMendixObject res = restReferences.get(url);
			if (res != null)
				return res;
			res = Core.instantiate(context, otherSideType);
			res.setValue(context, RestReference.MemberNames._url.toString(), url);
			Core.commit(context, res);
			restReferences.put(url, res);
			return res;
		}
		else if (!Core.isSubClassOf(RestObject.getType(), otherSideType))
			throw new Exception("Failed to load reference " + url + ": Not a subclass of RestObject: " + otherSideType);
		
		IMendixObject res = restObjects.get(url);
		if (res != null)
			return res;
		
		res = Core.instantiate(context, otherSideType);
		if (res.hasMember(RestServices.URL_ATTR))
			res.setValue(context, RestServices.URL_ATTR, url);
		restObjects.put(url, res);
		
		RestConsumer.getObject(context, url, res, this);
		return res;
	}

	public void putObject(String url, IMendixObject target) {
		restObjects.put(url, target);
	}

}
