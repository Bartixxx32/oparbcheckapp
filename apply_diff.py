import sys

def apply_diff(filepath, diff_filepath):
    with open(filepath, 'r') as f:
        lines = f.readlines()

    with open(diff_filepath, 'r') as f:
        diff_lines = f.readlines()

    search_block = []
    replace_block = []
    mode = None

    for line in diff_lines:
        if line.startswith('<<<<<<< SEARCH'):
            mode = 'search'
        elif line.startswith('======='):
            mode = 'replace'
        elif line.startswith('>>>>>>> REPLACE'):
            break
        elif mode == 'search':
            search_block.append(line)
        elif mode == 'replace':
            replace_block.append(line)

    content = "".join(lines)
    search_str = "".join(search_block)
    replace_str = "".join(replace_block)

    if search_str in content:
        new_content = content.replace(search_str, replace_str)
        with open(filepath, 'w') as f:
            f.write(new_content)
        print("Diff applied successfully!")
    else:
        print("Search block not found!")
        sys.exit(1)

if __name__ == "__main__":
    apply_diff(sys.argv[1], sys.argv[2])
