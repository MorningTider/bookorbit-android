#!/usr/bin/env python3
"""Check Android Chinese resource coverage and formatting arguments."""
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / 'app/src/main/res'
arguments = re.compile(r'%(?:\d+\$)?[a-zA-Z]')
errors = []
checked = 0

for file_name in ['strings.xml', 'plurals.xml']:
    source = ET.parse(root / 'values' / file_name).getroot()
    target = ET.parse(root / 'values-zh-rCN' / file_name).getroot()
    translations = {entry.attrib['name']: entry for entry in target}
    for entry in source:
        if entry.attrib.get('translatable') == 'false':
            continue
        name = entry.attrib['name']
        translated = translations.get(name)
        if translated is None:
            errors.append(f'{name} has no Chinese translation')
            continue
        checked += 1
        expected = Counter(arguments.findall(''.join(entry.itertext())))
        if entry.tag == 'plurals':
            expected = Counter(arguments.findall(entry.find('item').text or ''))
            for item in translated:
                if Counter(arguments.findall(item.text or '')) != expected:
                    errors.append(f'{name} has different Chinese plural arguments')
        elif Counter(arguments.findall(''.join(translated.itertext()))) != expected:
            errors.append(f'{name} has different Chinese format arguments')

if errors:
    raise SystemExit('\n'.join(errors))
print(f'Chinese resource coverage and format arguments passed for {checked} resources')
