try:
    from cairosvg import svg2png
    svg2png(url='app-icon.svg', write_to='app-icon.png', output_width=512, output_height=512)
    print('SUCCESS: app-icon.png created')
except ImportError:
    try:
        from PIL import Image
        import subprocess, os
        # Try Inkscape if available
        result = subprocess.run(['inkscape', 'app-icon.svg', '--export-png=app-icon.png', '-w', '512', '-h', '512'], capture_output=True)
        if result.returncode == 0:
            print('SUCCESS via Inkscape')
        else:
            print('Inkscape not found either. Use browser method below.')
    except Exception as e:
        print(f'Could not auto-convert: {e}')
        print('MANUAL METHOD: Open app-icon.svg in Chrome, right-click -> Save as image, or use https://svgtopng.com')
