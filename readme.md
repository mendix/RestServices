# Rest Services

![RestServices](images/logo.png) 

Welcome to the Rest Services module. This module can be used in [Mendix](http://www.mendix.com/) apps as toolkit if you want to achieve one of the following three purposes:

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
* `optResponseData` is an optional Mendix object in which the response data will be stored. This object will be filled by any JSON / file data which is in the response body of the request. See the 'JSON deserialization' section for more details how a JSON body is parsed into a Mendix object. 

This method returns a `RequestResult` object if the service responds with HTTP response `200 OK` or `304 Not Modified`. In all other cases an exception will be thrown. 

## The `RequestResult` object
Most REST operations return a `RequestResult` object which contains the meta information of a response. An instance contains the following fields:

* `ResponseCode` stating whether the server responded with 'OK'  or 'Not modified'. A 'Not modified' response might be send by the server if, for example, an 'If-none-modified' header was send, which indicates that you received the proper response to this request in an earlier request. See for example [ETags](http://en.wikipedia.org/wiki/HTTP_ETag)
* `RawResponseCode` idem, but as HTTP status code. Either '200' or '304'. 
* `ETag` if the response contained an `ETag` header, it is picked up and stored in this field. It can be used as optimization for any subsequent requests. 
* `ResponseBody` the full and raw response body of the request. This field is only set if the body of the response is not yet parsed (by providing an `optResponseData` parameter for example). 

## Sending request headers using `addHeaderToNextRequest`
Many REST services require the usage of custom request handlers for authentication, caching etc. In the RestServices module, any call to `addHeaderToNextRequest` will add a header to the *next* (and only the next) request that will be made by the current microflow. 

## Authentication
The RestServices module only supports *Basic authentication* out of the box. But it also possible to authenticate using OAuth or other security protocols by following the specs of the designated service. (There is a working Dropbox integration app based on a combination of the RestServices module and the Deeplink module for requesting tokens). 

Basic authentication credentials can be send by using `addCredentialsToNextRequest` just before the actual request is made. Too make life easier, it is also possible to use `registerCredentials`, which will send credentials with *any* subsequent request to the same host. 

## Other methods
For all standard HTTP verbs there is a method available which wraps the `request` operation, but simplifies the arguments one has to provide. See the 'HTTP Verbs' section for best practices about when to use which verb. 

### `get`
Tries to retrieve an object from the provided URL. Expects JSON data which will be parsed into the `stub` object. A `stub` object *should* be a just created, empty and transient object. This object will be filled as described in the 'JSON Deserialization' section. 

### `getCollection`
Tries to retrieve a list of objects from the provided URL. The expected response body is either an array of JSON objects, or an array of URLs, which will be retrieved automatically to retrieve all the items in the array. `getCollection` uses streaming to make the process as memory efficient as possible. 

The `resultList` parameter should be a completely empty list, but of the same type as `firstResult`. The `firstResult` object will be used to story the first result in and should be a new, empty, transient object. 

<small>Needing to provide both the `resultList` and `firstResult` parameters might seem a bit awkward, but the `firstResult` object is required by the actual implementation to determine the type of the result set, because lists in Mendix are untyped at runtime)</small>


### `getCollectionAsync`
The behavior is similar to `getCollection`, but its implementation is very suitable for very large response sets. Instead of building a list, the `callbackMicroflow` will be invoked on each individual item in the response array. This way, only one item is in memory of the server at any given time. 

### `post`
Submits an object to the remote server. The `dataObject` will be serialized to JSON as described in the 'JSON Serialization' section. If the `asFormData` parameter is set, the data will not be encoded as JSON but as form data (which is commonly used for submitting web forms for example). Note that form data only supports flat, primitive objects. 

### `delete`
Tries to delete a resource at the given URL. 

### `put`
Similar to `post`. See the 'HTTP verbs' section or the specs of the service you are consuming to find out whether to use `post` or `put`. 

# Publishing REST services

Publishing a REST service is pretty straight forward with this modules. The module provides publishing REST services in two flavors:

1. Publishing operations, based on a single microflow. 
2. Publishing a part of your data model, and providing a typical rest based API to retrieve, update, delete, create and even real-time sync data. 

PLEASE NOT THAT TO BE ABLE TO PUBLISH ANY SERVICE, THE MICROFLOW `STARTPUBLISHSERVICES` SHOULD BE CALLED DURING STARTUP OF THE APP!

## Publishing a microflow using `CreateMicroflowService`
Publishing a microflow is conceptually very similar to publishing a webservice. It publishing a single operation based on a microflow. The difference with a normal Mendix webservice is the transport method; instead of SOAP the RestServices module provides an interface which supports JSON based messages or form / multipart encoded messages (typically used to submit webforms). 

A published microflow should have a single transient object as argument. Each field in this transient object is considered a parameter (from HTTP perspective). Complex objects are supported if JSON is used as transport mechanism. The return type of the microflow should again be a transient object or a String. In the latter case, the string is considered to be the raw response of the operation which is not further processed. 

Publishing a microflow is as simple as calling `CreateMicroflowService` with the public name of the operation and the microflow that provides the implementation. The meta data of the operation is published on *&lt;app-url&gt;/rest/*, including a [JSON-schema](http://json-schema.org/) describing its arguments. The endpoint for the operation itself is *&lt;app-url&gt;/rest/&lt;public-name&gt;*

## Publishing a data service

### Introduction

A Published Service provides a JSON based API to list, retrieve, create, update, delete and track objects in your database. Documentation for these services will be generated automatically by the module and can be read by pointing your browser at *&lt;app-url&gt;/rest/*.

The central idea behind a service that there is a persistent entity in your database acting as data *source* for your service. Furthermore your model should define a transient object that will act as *view* object of your data, so that your internal data structure is not directly published to the outside. This allows for better maintainability and it guarantees that you can pre- or post-process your data when required. 

When publishing data, the *Publish Microflow* has the responsibility of converting *source* objects into *view objects*. When processing incoming data, the *Update Microflow* is responsible for exactly the reverse process; transforming a *view* as provided by some consumer into real data in your database. 

Each *source* object should be uniquely identifiable by a single *key* attribute. 

For example: *GET &lt;your-app&gt;/rest/&lt;service name&gt;/&lt;identifier&gt;* will search for the first *source* object in your database which *key* value equals the *identifier*. If found, this object will be converted by the *Publish Microflow* into a *view object*. This *view object* will be serialized to JSON (or HTML or XML) and returned to the consumer.  

### Creating a new service
Creating a new JSON based data service with full CRUD support is pretty straightforward with the RestServices module. The easiest way to start is to connect the microflow `IVK_OpenServiceOverview` to your navigation and create a new Published Service after starting your app. 

Once you have figured out the correct configuration for your data service, a best practice is to use the `GetOrCreateDataService` microflow in the startup microflow to create your service configuration. Alter and commit the properties of the resulting ServiceDefinition to update the configuration. If the configuration contains consistency errors, those will will be listed in the log of your application. 

### Configuring your service


### Using your service

Responses can also be rendered as XML or HTML, depending on the Accept headers of the request. 

# Data synchronization

# HTTP Verbs in Rest

# JSON Serialization

# JSON Deserialization

# Known Integrations

We know that the RestSevices module has already been used successfully to integrate with the following services:

* Dropbox.com
* Paydro.net
* Postcodeapi.nu
* Rijksmuseum.nl