#
# Copyright 2013 Tail-f Systems
#
#(C) 2017 Tail-f Systems

import ncs
import _ncs

import sys
import requests
import json
from nso_dry_run import format_dry_run

HTTP_RESP_200_OK         = 200
HTTP_RESP_201_CREATE     = 201
HTTP_RESP_204_NO_CONTENT = 204
HTTP_RESP_404_NOT_FOUND  = 404

BASE_URL = "http://127.0.0.1:8080/jsonrpc"
headers = {'Content-type': 'application/json'}
AUTH = ('admin', 'admin')

class NsoSession:

   def __init__(self, server, session, user, pswd):
      self.server = server
      self.session = requests.Session()
      self.session.auth = (user, pswd)
      self.idval = 1
      self.browser_session = session
      self.browser_session['change_set'] = 'none'
      self.baseurl = "http://" + server + ":8080/jsonrpc"

   def is_modified(self):
      self.idval += 1
      self.print_cmd( "is_trans_modified")
      payload = {
        "method" : "is_trans_modified",
        "params" : {
           "th" : self.th
        },
        "jsonrpc" : "2.0",
        "id" : self.idval
      }
      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      
      if resp.status_code == HTTP_RESP_200_OK:
        result = json.loads(resp.content)
        return(result['result']['modified'])
      
      return False

   def revert(self):
      self.idval += 1
      self.print_cmd("delete_trans")
      payload = {
        "method" : "delete_trans",
        "params" : {
           "th" : self.th
        },
        "jsonrpc" : "2.0",
        "id" : self.idval
      }
      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      
      if resp.status_code == HTTP_RESP_200_OK:
        result = json.loads(resp.content)
        if 'error' in result:
           return False
        ###
        # If the delete is successful the transaction is now closed
        # clear it out
        ###
        self.th = 0  
        self.idval += 1
        self.print_cmd("new_trans (read_write)")
        payload = {
           "method" : "new_trans",
           "params" : {
           "db" : "running",
           "mode" : 'read_write' 
          },
          "jsonrpc" : "2.0",
          "id" : self.idval,
        }
        resp = self.session.post(self.baseurl,
                                 headers=headers,
                                 data=json.dumps(payload))

        jsonret = json.loads(resp.content)

        if resp.status_code == HTTP_RESP_200_OK:
          self.browser_session['change_set'] = 'none'
          self.th = jsonret['result']['th']
          return True
        
      return False 

   def simple_query_top(self, xpath, selection):
      self.idval += 1
      self.print_cmd("query:" + xpath)
      payload = {
        "method" : "query",
        "params" : {
        "th" : self.th,
        "xpath_expr" : xpath,
        "selection" : selection,
        "chunk_size": 50,
        "initial_offset":1,
        "result_as":"string"
      },
      "jsonrpc" : "2.0",
      "id" : self.idval,
      }

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
        return json.loads(resp.content)

      return False

   def simple_query(self, path, xpath, selection):
      self.idval += 1
      self.print_cmd("query:" + path)
      payload = {
        "method" : "query",
        "params" : {
        "th" : self.th,
        "context_node" : path,
        "xpath_expr" : xpath,
        "selection" : selection,
        "chunk_size": 50,
        "initial_offset":1,
        "result_as":"string"
      },
      "jsonrpc" : "2.0",
      "id" : self.idval,
      }

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      
      if resp.status_code == HTTP_RESP_200_OK:
        return json.loads(resp.content)

      return False

   def count(self, path):
      self.idval += 1
      self.print_cmd("count_list_keys: " + path)
      payload = {
        "method" : "count_list_keys",
        "params" : {
           "th" : self.th,
           "path" : path
        },
        "jsonrpc" : "2.0",
        "id" : self.idval,
      }

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      
      if resp.status_code == HTTP_RESP_200_OK:
        result = json.loads(resp.content)

        return result['result']['count']

      return 0

   def exists(self, path):
      self.idval += 1
      self.print_cmd("exists: " + path)
      payload = {
        "method" : "exists",
        "params" : {
           "th" : self.th,
           "path" : path
        },
        "jsonrpc" : "2.0",
        "id" : self.idval,
      }

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      
      if resp.status_code == HTTP_RESP_200_OK:
        return json.loads(resp.content)

      return False
    
   def getValue(self, path):
      self.idval += 1
      self.print_cmd("get_value:" + path)
      payload = {
        "method" : "get_value",
        "params" : {
        "th" : self.th,
        "path" : path
      },
      "jsonrpc" : "2.0",
      "id" : self.idval,
      }
      
      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      
      if resp.status_code == HTTP_RESP_200_OK:
        data = json.loads(resp.content)
        if 'error' in data:
            return ""
        return data['result']['value']

      return ''

   def get(self, path):
      self.idval += 1
      self.print_cmd("show_config:" + path)
      payload = {
        "method" : "show_config",
        "params" : {
        "th" : self.th,
        "path" : path
      },
      "jsonrpc" : "2.0",
      "id" : self.idval,
      }

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      
      if resp.status_code == HTTP_RESP_200_OK:
        return json.loads(resp.content)
        
      return False

   def getNewTrans(self, mode):
     self.idval += 1
     self.print_cmd("new_trans (" + mode + ")")
     payload = {
        "method" : "new_trans",
        "params" : {
        "db" : "running",
        "mode" : mode 
       },
       "jsonrpc" : "2.0",
       "id" : self.idval,
     }
 
     resp = self.session.post(self.baseurl,
                              headers=headers,
                              data=json.dumps(payload))

     jsonret = json.loads(resp.content)

     if resp.status_code == HTTP_RESP_200_OK:
       self.th = jsonret['result']['th']
       return True
     
     self.th = 0
     return False

   def logout(self):
      self.idval += 1
      self.print_cmd("logout")
      payload = {
        "method" : "logout",
        "jsonrpc" : "2.0",
        "id" : self.idval,
      }
      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      if resp.status_code == HTTP_RESP_200_OK:
        return True
      return False
      
   def login(self, username, password):
      
      self.print_cmd("login")
      payload = {
        "method" : "login",
        "params" : {
          "user" : username,
          "passwd" : password
        },
        "jsonrpc" : "2.0",
        "id" : self.idval,
      }
      respd = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      
      if respd.status_code == HTTP_RESP_200_OK:
        resp =  json.loads(respd.content)
        if 'error' in resp:
          return False

        return True

      return False

   def append(self, pathStr, value) :
      self.idval += 1
      print_cmd(self.idval, "append_list_entry: " + pathStr + ": " + value)
      payload = {
        "method":"append_list_entry",
        "params":{
           "th": self.th,
           "path":pathStr,
           "value":value
        },
        "jsonrpc":"2.0",
        "id":self.idval,
      }
 
      resp = self.session.post(BASE_URL,
                               headers=headers,
                               data=json.dumps(payload))
      if resp.status_code == HTTP_RESP_200_OK:
         if 'error' in resp:
            return(False)
         self.browser_session['change_set'] = 'active'
         return True
      return False

   def set(self, pathStr, value) :
      self.idval += 1
      if (isinstance(value, basestring)):
        self.print_cmd("set_value: " + pathStr + ": " + value)
      else:
        self.print_cmd("set_value: " + pathStr)

      payload = {
        "method":"set_value",
        "params":{
           "th": self.th,
           "path":pathStr,
           "value":value
        },
        "jsonrpc":"2.0",
        "id":self.idval,
      }

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      if resp.status_code == HTTP_RESP_200_OK:
         if 'error' in resp:
            return(False)
         self.browser_session['change_set'] = 'active'
         return True
      return False

   def delete(self, pathStr) :
       self.idval += 1
       self.print_cmd("delete: " + pathStr)
       payload = {
         "method":"delete",
         "params":{
            "th":self.th,
            "path":pathStr,
         },
         "jsonrpc":"2.0",
         "id":self.idval,
       }

       resp = self.session.post(self.baseurl,
                                headers=headers,
                                data=json.dumps(payload))
       if resp.status_code == HTTP_RESP_200_OK:
         if 'error' in resp:
            return(False)

         self.browser_session['change_set'] = 'active'
         return True

       return False

   def create(self, pathStr) :
      self.idval += 1
      self.print_cmd("create: " + pathStr)
      payload = {
        "method":"create",
        "params":{
           "th":self.th,
           "path":pathStr,
        },
        "jsonrpc":"2.0",
        "id":self.idval,
      }

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      if resp.status_code == HTTP_RESP_200_OK:
         if 'error' in resp:
            return(False)
         self.browser_session['change_set'] = 'active'
         return True
      return False

   def action(self, action, device):
      self.idval += 1
      self.print_cmd("action: %s" % (action))
      payload = {
          "jsonrpc" : "2.0",
          "id" : self.idval,
          "method" : 'run_action',
          "params" : {
              "th" : self.th,
              "path" : action,
              "params" : {
                 "device" : device
              }
          }
      }

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
          data = json.loads(resp.content)
          if 'result' in data:
            return data['result']

      return None

   def dryRun(self, outformat):
      self.idval += 1
      self.print_cmd("commit-dry-run")
      payload = {
          "jsonrpc" : "2.0",
          "id" : self.idval,
          "method" : "action",
          "params" : {
              "th" : self.th,
              "path" : "/ncs:services/commit-dry-run",
              "params" : {
                 "outformat" : outformat
              }
          }
      }

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
          data = json.loads(resp.content)
          if 'result' in data:
            return format_dry_run(data, outformat)
          if 'error' in data:
            return None

      return None

   def commit(self) :
      self.idval += 1
      self.print_cmd("commit")
      payload = {
        "method" : "commit",
        "params" : {
          "th" : self.th
         },
        "jsonrpc" : "2.0",
        "id" : self.idval,
      }

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
       
         data = json.loads(resp.content)
         if 'error' in resp:
            return(data)

      self.browser_session['change_set'] = 'none'
      ###
      # If the commit is successful the transaction is now closed
      # clear it out
      ###
      self.th = 0  
      self.idval += 1
      self.print_cmd("new_trans (read_write)")
      payload = {
           "method" : "new_trans",
           "params" : {
           "db" : "running",
           "mode" : 'read_write' 
         },
         "jsonrpc" : "2.0",
         "id" : self.idval,
      }
      resp = self.session.post(self.baseurl,
                              headers=headers,
                              data=json.dumps(payload))

      jsonret = json.loads(resp.content)

      if resp.status_code == HTTP_RESP_200_OK:
        self.th = jsonret['result']['th']
        return data

      print 'Failed to create new transaction after commit'
      self.th = 0
      return False

   def validateCommit(self) :
     self.idval += 1
     self.print_cmd("validate_commit")
     payload = {
       "method" : "validate_commit",
       "params" : {
         "th" : self.th
       },
       "jsonrpc" : "2.0",
       "id" : self.idval,
     }

     resp = self.session.post(self.baseurl,
                              headers=headers,
                              data=json.dumps(payload))
     if resp.status_code == HTTP_RESP_200_OK:
       
       data = json.loads(resp.content)

       if 'error' in resp:
           return(data)
       return None 

     return {'errors' : {'type': 'validate_commit: NSO communication error', 'code:' : 0, }}

   def print_cmd(self, commandName):
      print ("   NSO[%s]=>JSON RPC (%d) method : %s" % (self.server, self.idval, commandName))

