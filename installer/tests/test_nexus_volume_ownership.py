import importlib.util
from pathlib import Path
import os
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace


spec = importlib.util.spec_from_file_location('ownership', str(
    Path(__file__).resolve().parents[1]/'lib/nexus-volume-ownership.py'))
vol = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vol)


class OwnershipTests(unittest.TestCase):
    def test_existing_volume_is_not_adopted(self):
        with patch.object(vol,'checked_directory',return_value=Path('/qa')), patch.object(vol,'get_receipt',return_value=None), patch.object(vol,'inspect_volume',return_value={'Name':vol.NAME}), patch.object(vol,'run') as run:
            vol.prepare(Path('/qa'),vol.NAME)
            run.assert_not_called()

    def test_name_and_receipt_both_required(self):
        with patch.object(vol,'run') as run:
            vol.prepare(Path('/qa'),'other-tenant')
            run.assert_not_called()
        with patch.object(vol,'get_receipt',return_value=None), patch.object(vol,'inspect_volume') as inspect:
            self.assertIsNone(vol.assessment(Path('/qa'))[0])
            inspect.assert_not_called()

    def test_a_reused_name_is_not_a_resource_identity(self):
        with patch.object(vol,'get_receipt',return_value={'label':'a'*48}), patch.object(vol,'inspect_volume',return_value={'Name':vol.NAME}), patch.object(vol,'evidence',return_value={'label':'b'*48}), patch.object(vol,'run') as run:
            self.assertIsNone(vol.assessment(Path('/qa'))[0])
            run.assert_not_called()

    def test_delete_rechecks_preview_and_never_forces(self):
        with patch.object(vol,'assessment',return_value=(None,'다른 컨테이너 참조')),patch.object(vol,'run') as run:
            with self.assertRaises(vol.VolumeError): vol.delete_empty(Path('/qa'),{'id':'old'})
            run.assert_not_called()
        with patch.object(vol,'assessment',return_value=({'id':'original'},'')),patch.object(vol,'run',return_value=SimpleNamespace(returncode=1)) as run:
            with self.assertRaises(vol.VolumeError): vol.delete_empty(Path('/qa'),{'id':'original'})
            run.assert_called_once_with(['docker','volume','rm',vol.NAME])

    def test_reject_network_or_bind_volume(self):
        for options,driver in [({'type':'nfs'},'local'),({'type':'none','o':'bind'},'local'),(None,'external')]:
            with self.assertRaises(vol.VolumeError):
                vol.evidence({'Driver':driver,'Options':options,'Scope':'local','CreatedAt':'x','Mountpoint':'/var/lib/docker/volumes/nexus-data/_data'})

    @unittest.skipUnless(hasattr(os, 'geteuid') and os.geteuid()==0,'Linux root')
    def test_private_receipt_requires_real_file(self):
        with tempfile.TemporaryDirectory(dir='/root') as root:
            top=Path(root); (top/vol.RECEIPT).symlink_to(top/'missing')
            with self.assertRaises(vol.VolumeError):vol.get_receipt(top)


if __name__=='__main__':
    unittest.main()
