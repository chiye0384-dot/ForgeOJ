// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
// Original explanation for the existing local demonstration seed, judge version 1.
export const legacySumSolution = {
  slug: 'sum-two-integers', judgeVersion: 1,
  datasetSha256: '37881a92ca996970e09475fdb29435b9bc13ae1501fa118e5fd9afd47e561adf',
  idea: `题意
输入两个有符号整数 a、b，输出 a + b。本题是标准输入输出练习，不是给定数组寻找两个下标的那道题。

核心思路
1. 从标准输入读取 a 和 b。
2. 使用 long 保存整数并计算它们的和。
3. 输出结果并换行。

为什么使用 long
题目保证输入和结果都在 64 位有符号整数范围内，但可能超出 int 的范围。不能先用 int 相加再转成 long。

正确性
程序读取的两个值正好是题目给定的 a 和 b；long 加法在题目保证的范围内得到精确的 a + b，输出即为所求。

复杂度
输入固定为两个 64 位整数，时间复杂度 O(1)，额外空间复杂度 O(1)。

提交方式
使用 public class Main 和 main 方法，从标准输入读取数据。直接粘贴力扣的 Solution 类或 twoSum 方法不符合这里的运行入口。`,
  sourceCode: `import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);
        long a = input.nextLong();
        long b = input.nextLong();
        System.out.println(a + b);
    }
}
`,
  independentCode: `import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

public class Main {
    public static void main(String[] args) throws Exception {
        String data = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        String[] values = data.trim().split("\\\\s+");
        BigInteger first = new BigInteger(values[0]);
        BigInteger second = new BigInteger(values[1]);
        System.out.println(first.add(second));
    }
}
`,
}
