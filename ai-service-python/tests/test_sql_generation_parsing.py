import unittest

from app.main import normalize_sql_generation, parse_generation_content


class SqlGenerationParsingTest(unittest.TestCase):
    def test_parse_json_generation(self):
        content = """
        {
          "plan": {"intent": "按地区统计销售金额", "tables": ["orders"]},
          "sql": "SELECT region, SUM(amount) AS total_amount FROM orders GROUP BY region",
          "explanation": "按地区聚合金额",
          "assumptions": ["amount 表示销售金额"]
        }
        """

        result = normalize_sql_generation(parse_generation_content(content), content)

        self.assertEqual(result["sql"], "SELECT region, SUM(amount) AS total_amount FROM orders GROUP BY region")
        self.assertEqual(result["plan"]["tables"], ["orders"])
        self.assertEqual(result["assumptions"], ["amount 表示销售金额"])

    def test_parse_markdown_json_generation(self):
        content = """```json
        {"sql": "SELECT customer_id, COUNT(*) AS total_count FROM orders GROUP BY customer_id"}
        ```"""

        result = normalize_sql_generation(parse_generation_content(content), content)

        self.assertEqual(result["sql"], "SELECT customer_id, COUNT(*) AS total_count FROM orders GROUP BY customer_id")

    def test_fallback_extracts_plain_sql(self):
        content = "SELECT region, SUM(amount) AS total_amount FROM orders GROUP BY region;"

        result = normalize_sql_generation(parse_generation_content(content), content)

        self.assertEqual(result["sql"], "SELECT region, SUM(amount) AS total_amount FROM orders GROUP BY region")


if __name__ == "__main__":
    unittest.main()
