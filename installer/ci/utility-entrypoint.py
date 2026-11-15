#!/usr/bin/env python3
"""Run only the bundled importer; isolate imports from mounts and PYTHONPATH."""
from pathlib import Path
import runpy
import sys


if not sys.flags.isolated or sys.version_info < (3, 10):
    raise SystemExit('Python 3.10+ isolated mode (-I) is required')
tools = Path(__file__).resolve().parent
sys.path.insert(0, str(tools))
sys.argv[0] = str(tools / 'nexus_seed_import.py')
runpy.run_path(sys.argv[0], run_name='__main__')

