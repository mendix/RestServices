package restservices.publish;

import restservices.proxies.ServiceDefinition;

public class ConsistencyChecker {
	public static String check(ServiceDefinition def) {
		// source exists and persistent
		// keyattr exists
		// constraint is valid
		// name is a valid identifier
		// reserved attributes: _key, _etag
		//TODO:
		return null;
	}
}
