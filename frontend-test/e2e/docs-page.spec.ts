import { test, expect } from '@playwright/test';

test.describe('Docs Page', () => {

  test('docs page has correct title', async ({ page }) => {
    await page.goto('/docs');
    await expect(page).toHaveTitle(/Docs/);
  });

  test('docs page displays heading', async ({ page }) => {
    await page.goto('/docs');
    await expect(page.locator('h1')).toContainText('Documentation');
  });

  test('docs page has description', async ({ page }) => {
    await page.goto('/docs');
    await expect(page.locator('main, section')).toContainText('Everything you need to know about ArchieMate');
  });

  test('docs page lists getting started entry', async ({ page }) => {
    await page.goto('/docs');
    await expect(page.locator('a')).toContainText('Getting Started');
  });

  test('docs page lists configuration entry', async ({ page }) => {
    await page.goto('/docs');
    await expect(page.locator('a')).toContainText('Configuration');
  });

  test('docs page lists API reference entry', async ({ page }) => {
    await page.goto('/docs');
    await expect(page.locator('a')).toContainText('API Reference');
  });

  test('docs page lists deployment entry', async ({ page }) => {
    await page.goto('/docs');
    await expect(page.locator('a')).toContainText('Deployment');
  });

  test('docs page lists contributing entry', async ({ page }) => {
    await page.goto('/docs');
    await expect(page.locator('a')).toContainText('Contributing');
  });

  test('navigating to /docs shows the docs page', async ({ page }) => {
    await page.goto('/docs');
    await expect(page.locator('h1')).toContainText('Documentation');
  });
});
