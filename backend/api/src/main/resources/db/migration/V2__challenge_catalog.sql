CREATE TABLE problems (
 slug varchar(64) PRIMARY KEY CHECK (slug ~ '^[a-z0-9-]+$'),
 title varchar(100) NOT NULL,
 difficulty varchar(8) NOT NULL CHECK (difficulty IN ('EASY','MEDIUM','HARD')),
 description text NOT NULL CHECK (octet_length(description) <= 4096),
 starter_code jsonb NOT NULL,
 public_tests jsonb NOT NULL,
 hidden_tests jsonb NOT NULL,
 enabled boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

ALTER TABLE submissions
  ADD COLUMN problem_slug varchar(64) REFERENCES problems(slug);

CREATE INDEX submissions_problem ON submissions(problem_slug, created_at DESC)
  WHERE problem_slug IS NOT NULL;

INSERT INTO problems(slug,title,difficulty,description,starter_code,public_tests,hidden_tests) VALUES
('fizz-buzz','FizzBuzz','EASY',
 'Read an integer n. Print the values 1 through n, one per line. Replace multiples of 3 with Fizz, multiples of 5 with Buzz, and multiples of both with FizzBuzz.',
 '{"PYTHON":"n = int(input())\n\n# TODO: print the sequence\n","JAVA":"import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner input = new Scanner(System.in);\n        int n = input.nextInt();\n        // TODO: print the sequence\n    }\n}\n","CPP":"#include <iostream>\n\nint main() {\n    int n;\n    std::cin >> n;\n    // TODO: print the sequence\n    return 0;\n}\n","JAVASCRIPT":"const fs = require(\"node:fs\");\nconst n = Number(fs.readFileSync(0, \"utf8\").trim());\n\n// TODO: print the sequence\n"}'::jsonb,
 '[{"input":"5\n","expected":"1\n2\nFizz\n4\nBuzz\n"}]'::jsonb,
 '[{"input":"1\n","expected":"1\n"},{"input":"15\n","expected":"1\n2\nFizz\n4\nBuzz\nFizz\n7\n8\nFizz\nBuzz\n11\nFizz\n13\n14\nFizzBuzz\n"},{"input":"16\n","expected":"1\n2\nFizz\n4\nBuzz\nFizz\n7\n8\nFizz\nBuzz\n11\nFizz\n13\n14\nFizzBuzz\n16\n"}]'::jsonb),
('palindrome','Palindrome Check','EASY',
 'Read one non-empty line. Print true if it reads exactly the same forward and backward; otherwise print false. Comparison is case-sensitive and includes spaces.',
 '{"PYTHON":"text = input()\n\n# TODO: print true or false\n","JAVA":"import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        String text = new Scanner(System.in).nextLine();\n        // TODO: print true or false\n    }\n}\n","CPP":"#include <iostream>\n#include <string>\n\nint main() {\n    std::string text;\n    std::getline(std::cin, text);\n    // TODO: print true or false\n    return 0;\n}\n","JAVASCRIPT":"const fs = require(\"node:fs\");\nconst text = fs.readFileSync(0, \"utf8\").replace(/\\r?\\n$/, \"\");\n\n// TODO: print true or false\n"}'::jsonb,
 '[{"input":"racecar\n","expected":"true\n"},{"input":"codegrid\n","expected":"false\n"}]'::jsonb,
 '[{"input":"a\n","expected":"true\n"},{"input":"Racecar\n","expected":"false\n"},{"input":"never odd or even\n","expected":"false\n"}]'::jsonb),
('factorial','Factorial','EASY',
 'Read an integer n from 0 through 12. Print n factorial. Remember that 0! equals 1.',
 '{"PYTHON":"n = int(input())\n\n# TODO: print n factorial\n","JAVA":"import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        int n = new Scanner(System.in).nextInt();\n        // TODO: print n factorial\n    }\n}\n","CPP":"#include <iostream>\n\nint main() {\n    int n;\n    std::cin >> n;\n    // TODO: print n factorial\n    return 0;\n}\n","JAVASCRIPT":"const fs = require(\"node:fs\");\nconst n = Number(fs.readFileSync(0, \"utf8\").trim());\n\n// TODO: print n factorial\n"}'::jsonb,
 '[{"input":"5\n","expected":"120\n"}]'::jsonb,
 '[{"input":"0\n","expected":"1\n"},{"input":"1\n","expected":"1\n"},{"input":"10\n","expected":"3628800\n"},{"input":"12\n","expected":"479001600\n"}]'::jsonb),
('maximum-value','Maximum Value','EASY',
 'The first input value is n, followed by n integers. Print the largest integer. Values may be negative.',
 '{"PYTHON":"values = list(map(int, input().split()))\nn, numbers = values[0], values[1:]\n\n# TODO: print the maximum of the n numbers\n","JAVA":"import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner input = new Scanner(System.in);\n        int n = input.nextInt();\n        // TODO: read n values and print the maximum\n    }\n}\n","CPP":"#include <iostream>\n\nint main() {\n    int n;\n    std::cin >> n;\n    // TODO: read n values and print the maximum\n    return 0;\n}\n","JAVASCRIPT":"const fs = require(\"node:fs\");\nconst values = fs.readFileSync(0, \"utf8\").trim().split(/\\s+/).map(Number);\nconst n = values[0];\nconst numbers = values.slice(1);\n\n// TODO: print the maximum of the n numbers\n"}'::jsonb,
 '[{"input":"5 3 9 2 7 4\n","expected":"9\n"}]'::jsonb,
 '[{"input":"1 -8\n","expected":"-8\n"},{"input":"4 -10 -2 -30 -4\n","expected":"-2\n"},{"input":"6 7 7 7 6 7 5\n","expected":"7\n"}]'::jsonb),
('pair-sum','Pair Sum','MEDIUM',
 'The first two input values are n and target, followed by n integers. Print true if two different positions add to target; otherwise print false.',
 '{"PYTHON":"values = list(map(int, input().split()))\nn, target = values[0], values[1]\nnumbers = values[2:]\n\n# TODO: print true or false\n","JAVA":"import java.util.Scanner;\n\npublic class Main {\n    public static void main(String[] args) {\n        Scanner input = new Scanner(System.in);\n        int n = input.nextInt();\n        int target = input.nextInt();\n        // TODO: read n values and print true or false\n    }\n}\n","CPP":"#include <iostream>\n\nint main() {\n    int n, target;\n    std::cin >> n >> target;\n    // TODO: read n values and print true or false\n    return 0;\n}\n","JAVASCRIPT":"const fs = require(\"node:fs\");\nconst values = fs.readFileSync(0, \"utf8\").trim().split(/\\s+/).map(Number);\nconst [n, target] = values;\nconst numbers = values.slice(2);\n\n// TODO: print true or false\n"}'::jsonb,
 '[{"input":"4 9 2 7 11 15\n","expected":"true\n"},{"input":"3 20 1 2 3\n","expected":"false\n"}]'::jsonb,
 '[{"input":"2 6 3 3\n","expected":"true\n"},{"input":"5 0 -3 1 3 9 12\n","expected":"true\n"},{"input":"4 8 4 1 2 7\n","expected":"true\n"},{"input":"4 100 10 20 30 40\n","expected":"false\n"}]'::jsonb);
