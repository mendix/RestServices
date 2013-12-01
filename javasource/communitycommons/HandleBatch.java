package communitycommons;

import java.util.ArrayList;
import java.util.HashMap;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IChangeBatch;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import communitycommons.proxies.LogLevel;

public class HandleBatch
{
	private HashMap<String,HashMap<String,IChangeBatch>> batchChangeMap = new HashMap<String, HashMap<String,IChangeBatch>>();

	private static HashMap<String, HandleBatch> _instanceMap = new HashMap<String, HandleBatch>();

	public static HandleBatch _getInstance (String sessionId) 
	{
		if( !_instanceMap.containsKey(sessionId) )
			_instanceMap.put(sessionId, new HandleBatch());

		return _instanceMap.get(sessionId);
	}

	public void createChangeBatch(IContext context, String objectType, String uniqueBatchIdentifier, IMendixObject changedObject) throws Exception
	{
		if( !batchChangeMap.containsKey(uniqueBatchIdentifier) )
		{
			HashMap<String, IChangeBatch> batchMap = new HashMap<String, IChangeBatch>();
			batchChangeMap.put(uniqueBatchIdentifier, batchMap);
		}

		IChangeBatch changeBatch;
		HashMap<String,IChangeBatch> changeBatchMap = batchChangeMap.get(uniqueBatchIdentifier);
		if(changeBatchMap.containsKey(objectType))
		{
			changeBatch = changeBatchMap.get(objectType);
			changeBatch.next(changedObject);
		}
		else
		{
			changeBatch = Core.changeBatch(context, new ArrayList<IMendixObject>(), 1000, false);
			changeBatch.next(changedObject);
			HashMap<String, IChangeBatch> batchMap = new HashMap<String, IChangeBatch>();
			batchMap.put(objectType, changeBatch);
			batchChangeMap.put(uniqueBatchIdentifier, batchMap);
		}
	}

	public boolean commitChangeBatch(String uniqueBatchIdentifier) throws Exception
	{
		HashMap<String,IChangeBatch> batchMap = batchChangeMap.remove(uniqueBatchIdentifier);
		if(batchMap != null && batchMap.size() > 0)
		{
			for (IChangeBatch changeBatchObj : batchMap.values()) 
				changeBatchObj.commit();
			batchMap.clear();
			return true;
		}
		Logging.log("CommunityCommons", LogLevel.Warning, "Unable to commit batch: batch not found or empty");
		return false;
	}
}

