import sys, glob, re
t = f = e = 0
fails = []
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    s = open(p).read()
    m = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', s)
    t += int(m[1]); f += int(m[2]); e += int(m[3])
    fails += re.findall(r'<testcase name="([^"]+)" classname="([^"]+)"[^>]*>\s*<failure message="([^"]{0,300})', s)
print(f"tests={t} failures={f} errors={e}")
for x in fails: print("FAIL", x)
