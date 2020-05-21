#
# Copyright 2013 Tail-f Systems
#
#(C) 2017 Tail-f Systems

import sys
import argparse
import json
import subprocess
import nso_api
import time
import os

from nso_api import NsoSession as nso

step=1
        
class Step:

    def __init__(self, nscc, ids, stp, path, debug=True):
         
         self.path = path
         self.name = stp['name']
         self.cmd_type  = stp['cmd-type']
         self.cmd  = False
         self.commit = False
         self.msg = False
         self.nso = nscc
         self.filename = False
         self.ops = False;
         self.paths = []
         self.cmd_list = []
         self.ids = ids

         if 'cmd-list' in stp.keys():
            self.cmd_list = stp['cmd-list']
         if 'cmd' in stp.keys():
            self.cmd = stp['cmd']
         if 'msg' in stp.keys():
            self.msg = stp['msg']
         if 'file' in stp.keys():
            self.filename = stp['file']
         if 'commit' in stp.keys():
            self.commit = stp['commit']
         if 'path' in stp.keys():
            self.paths = stp['path']

    def execute(self):
        switcher = {
          "apply-template"       : "execute_nso_apply_template",
          "cli"                  : "execute_cli_cmd",
          "sync-from"            : "execute_nso_sync_from",
          "load merge"           : "execute_load_merge",
          "delete"               : "execute_nso_delete",
          "commit"               : "execute_nso_commit",
          "commit-fail"          : "execute_nso_commit_fail",
          "cli"                  : "execute_cli_cmd",
          "pause"                : "wait_for_input",
          "display-cq-active"    : "display_cq_active",
          "display-cq-complete"  : "display_cq_completed",
          "commit queue wait"    : "wait_for_commit_queue_empty",
          "recover-failed-devices" : "recover_failed_devices"
        }
        stepDisplay(self.name)
        step_method_name = switcher.get(self.cmd_type, "Invalid Command")
        method = getattr(self, step_method_name, lambda: "Invalid Command")
        result = method()
  
        if not result:
          stepSuccess(msg='Failed')
        elif not result == 'complete':
          stepSuccess()


    def get_file(self):

        with open(self.path + '/' + self.filename, 'r') as f:
           data=f.readlines()
           line = ""
           for d in data:
               line += d 
           return line        

    def get_json_file(self):

        with open(self.path + '/' + self.filename, 'r') as f:
          datastore = json.load(f)
          return datastore

    def wait_for_input(self):
         msg = '\n\t\tHit return to continue'
         if self.msg:
           msg = self.msg
           stepSuccess()
         print("")
         answer = raw_input("\t\t{0}".format(msg + " : "))
         print ""
    
         return 'complete'

    def display_cq_completed(self):

        stepSuccess()

        print("\n\t\tID               Tag                  Status     Failed Devices Completed Device(s)")
        print("\t\t---------------- -------------------- ---------- -------------- ------------------------")
     
        for id in self.ids:
          
          query_info = ['id', 'tag', 'when', 'status', 'devices','failed/name', 'failed/reason', 'failed/failed-services']
          data = self.nso.simple_query("/ncs:devices/commit-queue/completed","queue-item[id=\'%d\']" % id, query_info)  
          cq = [ dict(zip(query_info, values)) for values in data['result']['results']][0]
          
          path = "/ncs:devices/commit-queue/completed/queue-item{%s}" % id
          cq['devices'] = ','.join(self.nso.getValue(path + "/devices"))

          data = self.nso.simple_query(path, "failed", ['name'])
          fdata  = [item for sublist in data['result']['results'] for item in sublist]
          cq['failed-devices'] = ','.join(fdata)

          print("\t\t{0:16} {1:20} {2:10} {3:14} {4:16}".format(cq['id'],
                                                                cq['tag'],
                                                                cq['status'],
                                                                cq['failed-devices'],
                                                                cq['devices']))
        print("")
        return 'complete'

    def display_cq_active(self):

        stepSuccess()
        query_info = ['id', 'age', 'tag','status', 'devices','waiting-for', 'is-atomic']
        data = self.nso.simple_query('/ncs:devices/commit-queue','queue-item', query_info)
        cqi = [ dict(zip(query_info, values)) for values in data['result']['results']]
        for cq in cqi:
          s = ','
          res = self.nso.getValue("/devices/commit-queue/queue-item{%s}/devices" % cq['id'])
          s = s.join(res)
          cq['devices'] = s
    
        if len(cqi):
          print("\n\t\tCommit Queue Entries: ({0})".format(len(cqi)))
          print("\n\t\tID               Tag                  Age    Status     Atomic Devices")
          print("\t\t---------------- -------------------- ------ ---------- ------ --------------------")
          for cq in cqi:
            print("\t\t{0:16} {1:20} {2:6} {3:10} {4:6} {5:12}".format(cq['id'],
                                                                cq['tag'],
                                                                cq['age'],
                                                                cq['status'],
                                                                cq['is-atomic'],
                                                                cq['devices']))
          print("\n")
        else:
          print("\n\t\t** Commit Queue is Empty **\n")

        return 'complete'

    def display_cq_flags(self):

        commit_options = self.commit.split(',')
        print("\n\t\tCommit Option Settings")
        print("\t\t" + '-' * 45)
        for co in commit_options:
           cdo = co.split( '=')
           print ("\t\t{0:26}: {1}".format(cdo[0], cdo[1]))

    def wait_for_commit_queue_empty(self):

      while(True):

        query_info = ['id']
        data = self.nso.simple_query('/ncs:devices/commit-queue','queue-item', query_info)
        cqi = [ dict(zip(query_info, values)) for values in data['result']['results']]
      
        if len(cqi):
          time.sleep(2)
        else:
          return True

    def execute_load_merge(self):

      if 'json' in self.filename:
        fdata = self.get_json_file()
        format = 'json'
      else:
        fdata = str(self.get_file())
        format = 'xml'
      
      mode='merge'
      if self.cmd:
         mode = self.cmd

      res = self.nso.load(fdata, format=format, mode=mode)
       
      if 'error' in res.keys():
        stepFail(msg=res['error']['data']['message'])
      else:
        stepSuccess()

      if format == 'xml':
         fdata = fdata.replace("\n", "\n\t\t")
         print("\n\t\t{0}".format(fdata))

      return 'complete'

    def recover_failed_devices(self):

         for id in self.ids:
          
          path = "/ncs:devices/commit-queue/completed/queue-item{%s}" % id
          data = self.nso.simple_query(path, "failed", ['name'])
          fdata  = [item for sublist in data['result']['results'] for item in sublist]
        
          if len(fdata):
             stepSuccess("Starting")
             print("\n\t\tIssuing sync-from for devices [{0}]\n".format(','.join(fdata)))
             self.execute_nso_sync_from(devices=fdata)
             return 'complete'
          else:
             return True


    def execute_nso_sync_from(self, devices=None):

        if len(devices):
           ###
           # Called indirectly 
           ###
           self.nso.solo_action("/ncs:devices/sync-from", {"device": devices})
        else:
           ###
           # Called from dispatcher
           ###
           self.nso.solo_action("/ncs:devices/sync-from", {"device" : self.cmd_list})
           return True

    def execute_nso_apply_template(self, devices=None):

        devices = self.cmd.split(',')
        
        results = [""]
        for dev in devices:
           path = "/ncs:devices/ncs:device{%s}/apply-template" % dev
           res = self.nso.solo_action(path, self.cmd_list)
           
           try:
              if res['result'][1]['value'] == 'ok':
                 results.append("\t\tApplied Template to device {0}".format(dev))
           except:
              stepSuccess("Failed")

              results.append("\t\tFailed to Apply template to device{0}".format(dev))
              for res in results:
                 print(res)
              print("")
              return 'complete'

        stepSuccess()
        for res in results:
           print(res)
        print("") 


        return 'complete'

    def execute_nso_delete(self):

      for path in self.paths:
        res = self.nso.delete(path)
        if res == False:
          stepFail(msg="Delete operation failed")
      return True

    def execute_nso_commit_fail(self):

      result = self.nso.validateCommit()

      if self.commit:
        flags = self.commit.split(',')

        result = self.nso.commit(flags=flags)
      else:
        result = self.nso.commit()

      if result == True:
        return False

      if 'error' in result.keys():
          stepSuccess("Commit Failed (expected)")
          print("\n\t\tERROR: {0}\n".format(result['error']['data']['message']))
          return 'complete'
    
      stepFail()
    
    def execute_nso_commit(self):

      result = self.nso.validateCommit()

      if self.commit:
         flags = self.commit.split(',')
         result = self.nso.commit(flags=flags)
      else:
         result = self.nso.commit()
    
      if 'error' in result.keys():
         stepFail(msg=result['error']['data']['message'])
    

      if 'commit_queue' in result.keys():
          if 'id' in result['commit_queue'].keys():
            stepSuccess()
           
            if self.commit:
               self.display_cq_flags()

            self.ids.append(result['commit_queue']['id'])
            print("\n\t\tcommit-queue id: {0}\n".format(result['commit_queue']['id']))
            return 'complete'        

      return True

    def execute_cli_cmd(self):
    
      cmds = []
      if (len(self.cmd_list) == 0):
         cmds.append(self.cmd)
      else:
         cmds = self.cmd_list
      
      for cmd in cmds:
         cmd_array = cmd.split() 
         p2 = subprocess.check_output(cmd_array, shell=False)
      
      return True


class Scenario:
     def __init__(self, nscc, scenario_dir, scenario_name, debug=False):

        self.gather = []
        self.scenario_dir = scenario_dir
        self.scenario_name = scenario_name
        self.cwd = os.getcwd()
        
        os.chdir(self.cwd + '/' + scenario_dir)
        
        self.path = self.cwd + '/' + scenario_dir + '/' + scenario_name + '/' 
        self.scenario = self.get_json_file(self.path + scenario_name + ".json") 
        self.nso = nscc
        self.ids = []

     def get_json_file(self,filename):

        with open(filename, 'r') as f:
          datastore = json.load(f)
          return datastore          

     def execute(self):

         print("\nStarting {0}\n".format(self.scenario['name']))

         for stp in self.scenario['steps']:
            step = Step(self.nso, self.ids, stp, self.path)
            step.execute()
         
         print("\nFinished {0}\n".format(self.scenario['name']))
        
def stepIncrease():
    global step
    step += 1

def stepDisplay(message):
    global step
    display = "\tStep(%d): %s" % (step, message)
    spaces = 90 - len(display)
    print display,
    print spaces * '.',
    step += 1

def stepFail(msg=None):
    print('Failed')
    if msg:
      print("\n\t\t {0}\n".format(msg))
    exit(0);

def stepSuccess(msg='Complete'):
    print msg


def get_json_file(filename):

        with open(filename, 'r') as f:
          datastore = json.load(f)
          return datastore

def main(argv):

     parser = argparse.ArgumentParser()
     parser.add_argument("-u", "--username", action='store', dest='username', default='admin', help="username")
     parser.add_argument("-p", "--port", action="store", dest='port', nargs='?', type=int, default=8080, help="NSOP port (default=8080)")
     parser.add_argument("-n", "--nso-server", action="store", dest='server', nargs='?', default='127.0.0.1', help="NSOP host default=localhost")
     parser.add_argument("-d", "--dir", action="store", dest='dir', nargs='?', default='commit-queue-scenarios',help="Scenario Directory")
     parser.add_argument("-s", "--scenario", action="store", dest='scenario', required=True, nargs='?', help="Scenario Name")
     args = parser.parse_args()   
     
     nscc = nso(args.server, None, args.username, 'admin', port=args.port, debug=False)
     nscc.login(args.username, 'admin')
     nscc.getNewTrans('read_write')
     
     scenario = Scenario(nscc, args.dir, args.scenario)

     scenario.execute()


     nscc.logout()

if __name__ == '__main__' :
    main(sys.argv[1:])
