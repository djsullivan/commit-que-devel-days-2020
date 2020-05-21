import sys
import requests
import json

HTTP_RESP_200_OK         = 200
HTTP_RESP_201_CREATE     = 201
HTTP_RESP_204_NO_CONTENT = 204
HTTP_RESP_404_NOT_FOUND  = 404

headers = {'Content-type': 'application/json'}
AUTH = ('admin', 'admin')

class NsoSession:

   def __init__(self, server, session, user, pswd, port=8080, debug=True):
      self.server = server
      self.session = requests.Session()
      self.session.auth = (user, pswd)
      self.user = user
      self.passwd = pswd
      self.idval = 1
      self.browser_session = session
      if self.browser_session:
         self.browser_session['change_set'] = 'none'
      self.baseurl = "http://" + server + ":" + str(port) + "/jsonrpc"
      self.debug = debug

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
          if self.browser_session:
            self.browser_session['change_set'] = 'none'
          self.th = jsonret['result']['th']
          return True
        
      return False 

   def load_rollback(self, filenumber, path='', selective=False):
      self.idval +=1
      self.print_cmd("load_rollback")
      payload = {
         "method" : 'load_rollback',
         "params" : {
           "th" : self.th,
           "nr" : filenumber,
           "selective" : selective
         },
        "jsonrpc" : "2.0",
        "id" : self.idval       
      }
      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
        jsonret = json.loads(resp.content)

      return jsonret

   def load(self, data, path="/", format='xml', mode='merge'):
      self.idval +=1
      self.print_cmd("load")    
      payload = {
        "method" : "load",
        "params" : {
          "th" : self.th,
          "data" : data,
          "path" : path,
          "format": format,
          "mode" : mode,
        },
        "jsonrpc" : "2.0",
        "id" : self.idval
      }
      
      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
        jsonret = json.loads(resp.content)
        return jsonret

      return False

   def show_config(self, path, oper=True, format='string'):
      self.idval += 1
      self.print_cmd("show_config")
      payload = {
        "method" : "show_config",
        "params" : {
          "th" : self.th,
          "path" : path,
          "result_as": format,
          "with_oper": True,
          "max_size" : 0

        },
        "jsonrpc" : "2.0",
        "id" : self.idval
      }
      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
        jsonret = json.loads(resp.content)
        return jsonret

      return False
      
   def get_rollbacks(self):
      self.idval +=1
      self.print_cmd("get_rollbacks")    
      payload = {
        "method" : "get_rollbacks",
        "jsonrpc" : "2.0",
        "id" : self.idval
      }
      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
        jsonret = json.loads(resp.content)

        return jsonret['result']

      return False      
   def get_service_points(self):
      self.idval +=1
      self.print_cmd("get_service_points")
      payload = {
        "method" : "get_service_points",
        "jsonrpc" : "2.0",
        "id" : self.idval,
      }
      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
        jsonret = json.loads(resp.content)

        return jsonret['result']['service_points']

      return False

   def get_schema(self, namespace, path):
      self.idval += 1
      self.print_cmd("get_schema: [%s]" % namespace)
      payload = {
        "method" : "get_schema",
        "params" : {
           "th" : self.th,
           "namespace" : namespace,
           "path" : path,
           "levels" : -1,
           "insert_values" : True,
           "evaluate_when_entries" : True,
           "stop_on_list" : True,
        },
      "jsonrpc" : "2.0",
      "id" : self.idval,
      }
      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
        jsonret = json.loads(resp.content)
        return jsonret['result']['data']
      return None

   def simple_query(self, path, xpath, selection):
      self.idval += 1
      self.print_cmd("query:" + path)
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

      if path:
        payload['params']['context_node']  = path

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      
      if resp.status_code == HTTP_RESP_200_OK:
        return json.loads(resp.content)

      return False

   def run_query(self, qh):
      self.idval += 1
      self.print_cmd("run_query: [%d]" % qh)
      payload = {
        "method" : "run_query",
        "params" : {
        "qh" : qh
      },
      "jsonrpc" : "2.0",
      "id" : self.idval,
      }
      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
        jsonret = json.loads(resp.content)
        return jsonret['result']

      return False

   def start_query(self, path, xpath, selection, chunk_size=100):
      self.idval += 1
      self.print_cmd("query:" + path)
      payload = {
        "method" : "start_query",
        "params" : {
        "th" : self.th,
        "xpath_expr" : xpath,
        "selection" : selection,
        "chunk_size": chunk_size,
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
        jsonret = json.loads(resp.content)
        return  jsonret['result']['qh']

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
        result = json.loads(resp.content)
        try:
          if result['result']['exists'] == True:
             return True
          else:
             return False
        except Exception as e:
          return False

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
        return data['result']['value']

      return 'None'

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
 
      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))
      if resp.status_code == HTTP_RESP_200_OK:
         if 'error' in resp:
            return(False)
         if self.browser_session:
           self.browser_session['change_set'] = 'active'
         return True
      return False

   def set(self, pathStr, value) :
      self.idval += 1
      self.print_cmd("set_value: " + pathStr + ": " + value)
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
         if self.browser_session:
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
            return(resp)

         if self.browser_session:
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
         if self.browser_session:
            self.browser_session['change_set'] = 'active'
         return True
      return False

   def redeployReconcile(self, action, dryrun=False, keep=False):
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
                 "reconcile": {}
              }
          }
      }
      if keep == False:
       payload['params']['params']['reconcile'] = {"discard-non-service-config" : ""}
       
      if dryrun:
        payload['params']['params']['dry-run']  = {"outformat" : "cli"}

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
          data = json.loads(resp.content)
          if 'result' in data:
            if not data['result']:
               return None
            return data
      
      return None

   def action(self, action, device = ""):
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
                 "device": device
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
          
      return "RPC ERROR Occurred"


   def solo_action(self, action, aparams={}, device = ""):
      self.idval += 1
      self.print_cmd("action: %s" % (action))
      payload = {
          "jsonrpc" : "2.0",
          "id" : self.idval,
          "method" : 'run_action',
          "params" : {
              "th" : self.th,
              "path" : action,
              "params" : aparams
          }
      }

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
          data = json.loads(resp.content)
          return data
      return None

   def dryRun(self, outformat):
      self.idval += 1
      self.print_cmd("commit-dry-run")
      payload = {
          "jsonrpc" : "2.0",
          "id" : self.idval,
          "method" : "run_action",
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
             error = data['error']
             if 'data' in error:
                return 'Dry Run Error: ' + error['data']['reason']

      return None

   def commit(self, flags=[]) :
      self.idval += 1
      self.print_cmd("commit")

      payload = {
        "method" : "commit",
        "params" : {
          "th" : self.th,
          "flags": flags
         },
        "jsonrpc" : "2.0",
        "id" : self.idval,
      }

      resp = self.session.post(self.baseurl,
                               headers=headers,
                               data=json.dumps(payload))

      if resp.status_code == HTTP_RESP_200_OK:
       
         cdata = json.loads(resp.content)
         if 'error' in cdata:
            return(cdata)
      cdata = cdata['result']
      if self.browser_session:
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
        return cdata

      print ('Failed to create new transaction after commit')
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

   def formatDryRun(self, data, format):
      if format == 'cli':
        return cli_format(data)
    
      if format == 'native':
        return native_format(data)
    
      return xml_format(data)  

   def print_cmd(self, commandName):
     if self.debug:
        print ("   NSO[%s]=>JSON RPC (%d) method : %s" % (self.server, self.idval, commandName))

def format_dry_run(data, format):
    
    if format == 'cli':
      return cli_format(data)
    
    if format == 'native':
      return native_format(data)
    
    return xml_format(data)

def cli_format(dry_data):

    options = {'cli/lsa-node/data':    "      lsa-node {\n",
               'cli/local-node/data' : "    local-node {\n"}

    nodes = ['cli/lsa-node/data', 'cli/local-node/data']
    res = "\ncli {\n    lsa-node  {\n    "
    indent = "        "
    for entry in dry_data['result']:

       if entry['name'] == 'cli/lsa-node/name':
          res += '    lsa-node {\n' 
          res += '        name  %s\n' % entry['value']
          res += '        data'
      
       if entry['name'] == 'cli/local-node/data':
          res += "    local-node {\n"

       if entry['name'] in nodes:
          out = entry['value']
          ###
          # Split the data into multiple lines
          ###
          lines = out.split("\n")
          index = 0
          cp = ""
          for line in lines:

              if line[5:11] == 'device':
                 line = "<p class=\"bg-gray-active\">" + indent + line + "</p>"
              elif line[:1] == '+':
                if cp == "":
                  cp = "p+"
                  line = "<p class=\"bg-success\">" + indent + line + "<br>"
                else:
                  line = indent + line + "<br>"
                if lines[index+1][:1] != '+':
                   line += "</p>"
                   cp = ""
             
              elif line[:1] == '-':
                if cp == "":
                  cp = "p+"
                  line = "<p class=\"bg-danger\">" + indent + line + "<br>"
                else:
                   line = indent + line + "<br>"
                if lines[index+1][:1] != '-':
                   line += "</p>"
                   cp = ""
              else:
                line = indent + line + "\n"
              res += line
              index += 1

    res = res + "    }\n}"
    return res
def native_format(dry_data):
    res = "\nnative {\n"
    dev =    '   device {\n'
    end =    '   }\n'
    name =   '       name '
    data =   '       data '
    indent = "            "

    for entry in dry_data['result']:

       if entry['name'] == 'native/device/name':
          res +=  dev + name + entry['value'] + "\n"
          continue;
       
       if entry['name'] == 'native/device/data':
          out = entry['value']
          lines = out.split("\n")
          if lines[0] == '':
            del lines[0]

          res = res + data + lines[0] + "\n"
          for line in lines[1:]:
             if line == '':
               continue
             res += indent + line + "\n"
          res += end
    res += "}"
    return res

def xml_format(dry_data):
    res = "\nresult-xml {\n"
    ident = "             "
    for entry in dry_data['result']:

       if entry['name'] == 'result-xml/local-node/data':

          res += "    local-node {\n"
          lines = entry['value'].split('\n')
          res += "        data " + lines[0] + "\n"

          for line in lines[1:]:
            if line:
              res += ident + line + "\n"

          res += "    }\n"
    res += "}"
    return res

dry_run_format = {'native/device/name' : {'header': "\n    device {\n        name  ", 'footer': ""},
                  'native/device/data' : {'header': "\n        data  ", 'footer' : "    }"},
                  'cli/local-node/data' : {'header': "\n<p class=\"bg-success \">cli {</p>\n    local-node {\n       ", 'footer': ""},
                  'result-xml/local-node/data': {'header': "\nresult-xml {\n    local-node {\n         ", 'footer' : ""}}


