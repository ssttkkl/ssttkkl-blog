---
title: 【题解】AtCoder Beginner Contest 379 D - Home Garden
date: 2024-11-18 01:44:09
---

https://atcoder.jp/contests/abc379/tasks/abc379_d

随着时间流逝所有植物都会长高，实际上只需要记录一个delta，种植新植物时高度计为-delta，查询时将查询值减去这个delta再查即可。

开始想的是用一个堆去存储所有的植物，后来不难发现换成队列的话这个队列仍是单调递减的（先种的植物一定更高）。于是：

- 1：种植新植物，队列尾部入队-delta
- 2：时间流逝T，delta += T
- 3：移除所有高度大于等于H的植物，从队列首部移除所有大于等于H - delta的元素

```python
from collections import deque


q = int(input())
queue = deque()

delta = 0

while q > 0:
    q -= 1
    line = [int(x) for x in input().split(" ")]

    if line[0] == 1:
        queue.append(-delta)
    elif line[0] == 2:
        t = line[1]
        delta += t
    elif line[0] == 3:
        h = line[1] - delta
        cnt = 0
        while len(queue) > 0 and queue[0] >= h:
            cnt += 1
            queue.popleft()
        print(cnt)
```
