package restservices.publish;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.IDataType.DataTypeEnum;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import restservices.RestServices;
import restservices.publish.RestServiceRequest.ContentType;
import restservices.util.JsonDeserializer;
import restservices.util.JsonSerializer;
import restservices.util.Utils;

public class PublishedMicroflow {
	
	private String microflowname;
	private boolean hasArgument;
	private String argType;
	private boolean isReturnTypeString;
	private String returnType;
	private String argName;

	public PublishedMicroflow(String microflowname){
		this.microflowname = microflowname;
		this.consistencyCheck();
		RestServices.registerPublishedMicroflow(microflowname, this);
	}

	private void consistencyCheck() {
		int argCount = Utils.getArgumentTypes(microflowname).size(); 
		
		if (argCount > 1)
			throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", it should exist and have exactly zero or one argument");
		
		hasArgument = argCount == 1;
		if (hasArgument) {
			IDataType argtype = Utils.getFirstArgumentType(microflowname);
			if (!argtype.isMendixObject())
				throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", it should have a single object as input argument");
			this.argType = argtype.getObjectType();
			this.argName = Utils.getArgumentTypes(microflowname).keySet().iterator().next();
		}
		
		if (Core.getMetaObject(argType).isPersistable())
			throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", it should have a single object as input argument");
		
		IDataType returnType = Core.getReturnType(microflowname);
		this.isReturnTypeString = returnType.getType() == DataTypeEnum.String; 
		if (!isReturnTypeString) {
			if (!returnType.isMendixObject() || !returnType.isList())
				throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", its return type should be a String, List or Object type");
			if (returnType.isMendixObject() || returnType.isList()){
				this.returnType = returnType.getObjectType();
				if (Core.getMetaObject(this.returnType).isPersistable())
					throw new IllegalArgumentException("Cannot publish microflow " + microflowname+ ", its return type should be a non-persistable object");
			}
		}
	}
	
	void execute(RestServiceRequest rsr) throws Exception {
		
		Map<String, Object> args = new HashMap<String, Object>();
		if (hasArgument) {
			IMendixObject argO = Core.instantiate(rsr.getContext(), argType);
			JSONObject data;
			
			if (rsr.getContentType() == ContentType.JSON) { 
				String body = IOUtils.toString(rsr.request.getInputStream());
				data = new JSONObject(body);
			}
			else
				data = new JSONObject();

			for (String param : rsr.request.getParameterMap().keySet())
				data.put(param, rsr.request.getParameter(param));
			
			JsonDeserializer.readJsonDataIntoMendixObject(rsr.getContext(), data, argO, false);
			args.put(argName, argO);
		}
		
		Object result = Core.execute(rsr.getContext(), microflowname, args);
		if (result == null) {
			//write nothing
		}
		else if (this.isReturnTypeString) {
			rsr.write(String.valueOf(result));
		}
		else if (result instanceof List<?>) {
			rsr.datawriter.array();
			for(Object item : (List<?>)result)
				rsr.datawriter.value(JsonSerializer.writeMendixObjectToJson(rsr.getContext(), (IMendixObject) item));
			rsr.datawriter.endArray();
			
		}
		else if (result instanceof IMendixObject) {
			rsr.datawriter.value(JsonSerializer.writeMendixObjectToJson(rsr.getContext(), (IMendixObject) result));
		}
		else throw new IllegalStateException("Unexpected result from microflow " + microflowname + ": " + result.getClass().getName());
	}
}
