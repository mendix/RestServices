# Rest Services

Welcome to the Mendix Rest Services module. This module can be used as toolkit if you want to achieve one of the following three purposes:

1. Consume JSON REST based services
2. Publish data or microflows through REST API's
3. (Real time) Synchronization of data between Mendix applications

# Consuming REST services

This module is able to invoke most, if not any, REST service which is based on JSON, form-data or multipart data (that is; files). The operations in the 'Consume' folder of the module provide the necessary tools to invoke data. The work horse of all this operations is the java action `request`. Most other methods are wrappers around this operation. 

## The `Request` java action
Request performs an HTTP request and provides the means to both send data and receive data over HTTP. Its parameters are defined as follow. 

* `method`, the HTTP method to use, which is one of `GET`, `POST`, `PUT` or `DELETE`. Which method to use is usually defined in the documentation of the service to consume. Otherwise see the 'HTTP verbs in REST' section for more information. As a rule of thumb: If you only want to fetch data use `GET`, otherwise use `POST`.
* `url` defines the HTTP endpoint to which the request should be send. 
* `optRequestData` is an optional Mendix object that provides the parameters which are used in the request (note that it is also possible to pass parameters in the `url` itself). This object will be serialized to JSON (see the corresponding section) and be passed to the endpoint. The format in which the data will be send depends on the actual `method` and `sendWithFormEncoding` parameter. 
* `optResponseData` is an optional Mendix object in which the response data will be stored. This object will be filled by any JSON / file data which is in the responsebody of the request. See the 'JSON deserialization' section for more details how a JSON body is parsed into a Mendix object. 

This method returns a `RequestResult` object if the service responds with HTTP response `200 OK` or `304 Not Modified`. In all other cases an exception will be thrown. 

## The `RequestResult` object
Most REST operations return a `RequestResult` object which contains the meta information of a response. An instance contains the following fields:

* `ResponseCode` stating whether the server responded with 'OK'  or 'Not modified'. A 'Not modified' response might be send by the server if, for example, an 'If-none-modified' header was send, which indicates that you received the proper response to this request in an earlier request. See for example [http://en.wikipedia.org/wiki/HTTP_ETag](Etags)
* `RawResponseCode` idem, but as HTTP status code. Either '200' or '304'. 
* `ETag` if the response contained an `ETag` header, it is picked up and stored in this field. It can be used as optimization for any subsequent requests. 
* `ResponseBody` the full and raw response body of the request. This field is only set if the body of the response is not yet parsed (by providing an `optResponseData` parameter for example). 

## Sending request headers using `addHeaderToNextRequest`
Many REST services require the usage of custom request handlers for authentication, caching etc. In the RestServices module, any call to `addHeaderToNextRequest` will add a header to the *next* (and only the next) request that will be made by the current microflow. 

## Authentication
The RestServices module only supports *Basic authentication* out of the box. But it also possible to authenticate using OAuth or other security protocols by following the specs of the designated service. (There is a working Dropbox integration app based on a combination of the RestServices module and the Deeplink module for requesting tokens). 

Basic authentication credentials can be send by using `addCredentialsToNextRequest` just before the actual request is made. Too make life easier, it is also possible to use `registerCredentials`, which will send credentials with *any* subsequent request to the same host. 

## Other methods
For all standard Http verbs there is a method available which wraps arounds the `request` operation, but simplifies the arguments one has to provide. See the 'HTTP Verbs' section for best practices about when to use which verb. 

### `get`
Tries to retrieve an object from the provided URL. Expects JSON data which will be parsed into the `stub` object. A `stub` object *should* be a just created, empty and transient object. This object will be filled as described in the 'JSON Deserialization' section. 

### `getCollection`
Tries to retrieve a list of objects from the provided URL. The expected response body is either an array of JSON objects, or an array of URLs, which will be retreived automatically to retrieve all the items in the array. `getCollection` uses streaming to make the process as memory efficient as possible. 

The `resultList` parameter should be a completely empty list, but of the same type as `firstResult`. The `firstResult` object will be used to story the first result in and should be a new, empty, transient object. 

<small>Needing to provide both the `resultList` and `firstResult` parameters might seem a bit awkward, but the `firstResult` object is required by the actual implementation to determine the type of the result set, because lists in Mendix are untyped at runtime)</small>


### `getCollectionAsync`
The behavior is similar to `getCollection`, but its implementation is very suitable for very large response sets. Instead of building a list, the `callbackMicroflow` will be invoked on each individual item in the response array. This way, only one item is in memory of the server at any given time. 

### `post`
Submits an object to the remote server. The `dataObject` will be serialized to JSON as described in the 'JSON Serialization' section. If the `asFormData` parameter is set, the data will not be encoded as JSON but as form data (which is commonly used for submitting web forms for example). Note that formdata only supports flat, primitive objects. 

### `delete`
Tries to delete a resource at the given URL. 

### `put`
Similar to `post`. See the 'HTTP verbs' section or the specs of the service you are consuming to find out whether to use `post` or `put`. 

# Publishing REST services

# Data synchronization

# HTTP Verbs in Rest

# JSON Serialization

# JSON Deserialization

