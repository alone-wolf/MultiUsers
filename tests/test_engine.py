"""Host orchestration only: Android command doubles do not validate SELinux/FBE/UID/su.

Doubles include `id`, so uid 0 is NOT required. Linux/GNU coreutils are required
(flock, sha256sum, stat -c); a host missing them fails loudly instead of skipping.
Failure cases assert the specific rejection reason, so an environment error can no
longer masquerade as a correctly rejected operation."""
import json,os,pathlib,subprocess,tempfile,unittest,uuid
ENGINE=pathlib.Path(__file__).resolve().parents[1]/'migration/src/main/assets/engine.sh'
MOCK=r'''#!/usr/bin/env python3
import os,sys,json,subprocess,pathlib
cmd=pathlib.Path(sys.argv[0]).name;a=sys.argv[1:];root=pathlib.Path(os.environ['FIXTURE_ROOT']);f=root/'state.json';s=json.loads(f.read_text());pkg='test.example'
if cmd=='id':print(0);sys.exit(0)
def user():return int(a[a.index('--user')+1]) if '--user' in a else 0
def uid(u):return u*100000+10001
def save():f.write_text(json.dumps(s))
if cmd=='am':
 if a[0]=='get-started-user-state':print('RUNNING_LOCKED' if s.get('locked') else 'RUNNING_UNLOCKED')
elif cmd=='pm':
 if a[:2]==['list','packages']:
  if '-s' in a:pass
  elif '-U' in a:
   print('package:'+pkg+' uid:'+str(uid(user())))
   if s.get('shared'):print('package:other.app uid:'+str(uid(user())))
 elif a[0]=='path':print('package:'+str(root/'apk/base.apk'))
 elif a[0]=='install-existing':print('Package installed')
 else:s['enabled'][str(user())]={'default-state':0,'enable':1,'disable':2,'disable-user':3,'disable-until-used':4}[a[0]];save()
elif cmd=='dumpsys':
 if a[0]=='user':
  for u in s['enabled']:print(' UserInfo{'+u+':Name:0} serialNo='+str(int(u)+s.get('serial_offset',0)))
 else:
  for u,v in s['enabled'].items():print(' User '+u+': installed=true enabled='+str(v))
elif cmd=='stat':
 if a[:2] in [['-c','%u'],['-c','%g']]:
  parts=pathlib.Path(a[2]).parts;u=int(parts[parts.index('user' if 'user' in parts else 'user_de')+1]);print(uid(u))
 else:sys.exit(subprocess.call(['/usr/bin/stat']+a))
elif cmd=='ls':print('u:object_r:app_data_file:s0:c1,c2 '+a[-1])
elif cmd in ['chown','chgrp','chcon','restorecon']:
 if cmd=='restorecon' and s.get('fail_context'):sys.exit(1)
else:raise RuntimeError(cmd)
'''
class EngineTests(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name);self.bin=self.root/'bin';self.bin.mkdir();self.jobs=self.root/'jobs';self.state={'enabled':{'0':0,'10':1}};self.write_state()
  for cmd in ['id','pm','am','dumpsys','stat','ls','chown','chgrp','chcon','restorecon']:
   f=self.bin/cmd;f.write_text(MOCK);f.chmod(0o755)
  for u in [0,10]:
   for space in ['user','user_de']:
    d=self.root/space/str(u)/'test.example/files';d.mkdir(parents=True);(d/'seed').write_text(f'{space}-{u}')
  (self.root/'apk').mkdir();(self.root/'apk/base.apk').write_text('same device apk')
  s=ENGINE.read_text().replace('export PATH=/system/bin:/system/xbin:/vendor/bin',f'export PATH={self.bin}:/usr/bin:/bin').replace('ROOT=/data/local/multiusers',f'ROOT={self.jobs}').replace('/data/user_de',str(self.root/'user_de')).replace('/data/user',str(self.root/'user')).replace('package:/data/app/*',f'package:{self.root}/apk/*')
  self.script=self.root/'engine.sh';self.script.write_text(s);self.env=dict(os.environ,FIXTURE_ROOT=str(self.root))
 def tearDown(self):self.tmp.cleanup()
 def write_state(self):(self.root/'state.json').write_text(json.dumps(self.state))
 def run_engine(self,mode,back='',ok=True,expect=None):
  tid=str(uuid.uuid4());r=subprocess.run(['bash',str(self.script),mode,tid,'test.example','0','10',back],env=self.env,capture_output=True,text=True);log=self.jobs/tid/'log';text=log.read_text() if log.exists() else r.stdout+r.stderr
  if ok:self.assertEqual(r.returncode,0,text)
  else:
   self.assertNotEqual(r.returncode,0,text)
   if expect:self.assertIn(expect,text,f'engine failed for the wrong reason, expected {expect!r}: {text}')
  return tid
 def seed(self,u):return (self.root/'user'/str(u)/'test.example/files/seed').read_text()
 def test_copy_then_recovery(self):
  original=self.seed(10);source=self.seed(0);id=self.run_engine('copy');self.assertEqual(self.seed(10),source);self.assertEqual(self.seed(0),source);self.run_engine('verify',id);self.run_engine('recover',id);self.assertEqual(self.seed(10),original);self.assertEqual(json.loads((self.root/'state.json').read_text())['enabled'],{'0':0,'10':1})
 def test_corrupt_backup_blocks_restore(self):
  id=self.run_engine('backup');(self.jobs/id/'source/ce.tar').write_bytes(b'bad');target=self.seed(10);self.run_engine('restore',id,False,expect='backup checksum mismatch');self.assertEqual(target,self.seed(10))
 def test_locked_user_rejected(self):
  self.state['locked']=True;self.write_state();self.run_engine('copy',ok=False,expect='must be running and unlocked');self.assertEqual(self.seed(10),'user-10')
 def test_missing_source_data_is_named(self):
  import shutil;shutil.rmtree(self.root/'user/0/test.example');shutil.rmtree(self.root/'user_de/0/test.example')
  self.run_engine('copy',ok=False,expect='data directory unavailable (source user 0)')
 def test_missing_target_data_is_named(self):
  import shutil;shutil.rmtree(self.root/'user/10/test.example');shutil.rmtree(self.root/'user_de/10/test.example')
  self.run_engine('copy',ok=False,expect='data directory unavailable (target user 10)')
 def test_shared_uid_rejected(self):
  self.state['shared']=True;self.write_state();self.run_engine('copy',ok=False,expect='shared UID not supported');self.assertEqual(self.seed(10),'user-10')
 def test_symlink_rejected_and_source_enabled_restored(self):
  (self.root/'user/0/test.example/files/link').symlink_to('/etc/passwd');self.run_engine('copy',ok=False,expect='symlink/special file unsupported');self.assertEqual(json.loads((self.root/'state.json').read_text())['enabled']['0'],0)
 def test_failed_write_retains_snapshot_then_recovers(self):
  self.state['fail_context']=True;self.write_state();original=self.seed(10);id=self.run_engine('copy',ok=False,expect='WRITING');self.assertTrue((self.jobs/id/'target/COMPLETE').exists());state=json.loads((self.root/'state.json').read_text());self.assertEqual(state['enabled']['10'],3);state['fail_context']=False;(self.root/'state.json').write_text(json.dumps(state));self.run_engine('recover',id);self.assertEqual(self.seed(10),original)
 def test_reused_user_id_blocks_recovery(self):
  id=self.run_engine('copy');state=json.loads((self.root/'state.json').read_text());state['serial_offset']=100;(self.root/'state.json').write_text(json.dumps(state));target=self.seed(10);self.run_engine('recover',id,False,expect='source user identity changed');self.assertEqual(target,self.seed(10))
 def test_apk_change_blocks_restore(self):
  id=self.run_engine('backup');(self.root/'apk/base.apk').write_text('updated');target=self.seed(10);self.run_engine('restore',id,False,expect='APK changed; restore blocked');self.assertEqual(target,self.seed(10))
 def test_prewrite_failure_restores_enabled_without_overwrite(self):
  (self.root/'user/10/test.example/files/link').symlink_to('/etc/passwd');id=self.run_engine('copy',ok=False,expect='symlink/special file unsupported');self.run_engine('recover',id);self.assertEqual(json.loads((self.root/'state.json').read_text())['enabled'],{'0':0,'10':1});self.assertEqual(self.seed(10),'user-10')
if __name__=='__main__':unittest.main(verbosity=2)
