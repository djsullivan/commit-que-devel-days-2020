#
# Copyright 2013 Tail-f Systems
#
#(C) 2017 Tail-f Systems

import json
import nso_api


dry_run_format = {'native/device/name' : {'header': "\n    device {\n        name  ", 'footer': ""},
                  'native/device/data' : {'header': "\n        data  ", 'footer' : "    }"},
                  'cli/local-node/data' : {'header': "\n<p class=\"bg-success \">cli {</p>\n    local-node {\n       ", 'footer': ""},
                  'result-xml/local-node/data': {'header': "\nresult-xml {\n    local-node {\n         ", 'footer' : ""}}

def format_dry_run(data, format):
    
    if format == 'cli':
      return cli_format(data)
    
    if format == 'native':
      return native_format(data)
    
    return xml_format(data)

def cli_format(dry_data):

    res = "\ncli {\n"
    indent = "        "
    for entry in dry_data['result']:

       if entry['name'] == 'cli/local-node/data':
          out = entry['value']
          res = res + "    local-node {\n"
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
