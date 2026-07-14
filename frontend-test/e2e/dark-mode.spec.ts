import { test, expect } from '@playwright/test';

test.describe('Dark Mode Toggle', () => {

  test('starts in light mode by default', async ({ page }) => {
    await page.goto('/');
    // In light mode, documentElement should NOT have the 'dark' class
    await expect(page.locator('html')).not.toHaveClass(/dark/);
    // Body should have light mode classes
    const body = page.locator('body');
    await expect(body).toHaveClass(/bg-white/);
  });

  test('toggle button is visible in header', async ({ page }) => {
    await page.goto('/');
    const toggleButton = page.locator('button[aria-label="Toggle dark mode"]');
    await expect(toggleButton).toBeVisible();
  });

  test('toggle button shows moon SVG in light mode', async ({ page }) => {
    await page.goto('/');
    const toggleButton = page.locator('button[aria-label="Toggle dark mode"]');
    // In light mode, the toggle button should contain a moon SVG
    await expect(toggleButton.locator('svg')).toHaveCount(1);
    // Moon path contains the characteristic arc shape
    await expect(toggleButton.locator('svg path')).toHaveAttribute('d', /M21 12.79A9/);
  });

  test('clicking toggle activates dark mode', async ({ page }) => {
    await page.goto('/');
    const toggleButton = page.locator('button[aria-label="Toggle dark mode"]');
    await toggleButton.click();
    // After toggle, documentElement should have the 'dark' class
    await expect(page.locator('html')).toHaveClass(/dark/);
  });

  test('toggle button shows sun SVG in dark mode', async ({ page }) => {
    await page.goto('/');
    const toggleButton = page.locator('button[aria-label="Toggle dark mode"]');
    await toggleButton.click();
    // In dark mode, the toggle button should contain a sun SVG
    await expect(toggleButton.locator('svg')).toHaveCount(1);
    // Sun path contains the characteristic circle and rays
    await expect(toggleButton.locator('svg path')).toHaveAttribute('d', /M16 12a4/);
  });

  test('clicking toggle again returns to light mode', async ({ page }) => {
    await page.goto('/');
    const toggleButton = page.locator('button[aria-label="Toggle dark mode"]');
    // Toggle to dark mode
    await toggleButton.click();
    await expect(page.locator('html')).toHaveClass(/dark/);
    // Toggle back to light mode
    await toggleButton.click();
    await expect(page.locator('html')).not.toHaveClass(/dark/);
  });

  test('dark mode applies correct background colors', async ({ page }) => {
    await page.goto('/');
    // Light mode: header wrapper has white background
    const header = page.locator('div.border-b').first();
    await expect(header).toHaveClass(/bg-white/);
    
    // Toggle to dark mode
    const toggleButton = page.locator('button[aria-label="Toggle dark mode"]');
    await toggleButton.click();
    
    // Dark mode: header has dark background
    await expect(header).toHaveClass(/bg-gray-900/);
  });

  test('dark mode persists in localStorage', async ({ page }) => {
    await page.goto('/');
    const toggleButton = page.locator('button[aria-label="Toggle dark mode"]');
    
    // Toggle to dark mode
    await toggleButton.click();
    await expect(page.locator('html')).toHaveClass(/dark/);
    
    // Verify localStorage has the theme set
    const theme = await page.evaluate(() => localStorage.getItem('theme'));
    expect(theme).toBe('dark');
  });

  test('dark mode is restored on page reload', async ({ page }) => {
    await page.goto('/');
    const toggleButton = page.locator('button[aria-label="Toggle dark mode"]');
    
    // Toggle to dark mode
    await toggleButton.click();
    await expect(page.locator('html')).toHaveClass(/dark/);
    
    // Reload the page
    await page.reload();
    
    // Dark mode should still be active
    await expect(page.locator('html')).toHaveClass(/dark/);
  });

  test('footer respects dark mode', async ({ page }) => {
    await page.goto('/');
    const footer = page.locator('footer');
    
    // In light mode, footer has light background
    await expect(footer).toHaveClass(/bg-gray-100/);
    
    // Toggle to dark mode
    const toggleButton = page.locator('button[aria-label="Toggle dark mode"]');
    await toggleButton.click();
    
    // In dark mode, footer has dark background
    await expect(footer).toHaveClass(/bg-gray-800/);
  });

  test('header text color changes with dark mode', async ({ page }) => {
    await page.goto('/');
    const logoLink = page.locator('a[href="/"]');
    
    // Light mode: indigo-600 text
    await expect(logoLink).toHaveClass(/text-indigo-600/);
    
    // Toggle to dark mode
    const toggleButton = page.locator('button[aria-label="Toggle dark mode"]');
    await toggleButton.click();
    
    // Dark mode: indigo-400 text
    await expect(logoLink).toHaveClass(/text-indigo-400/);
  });

  test('toggle button SVG changes between sun and moon', async ({ page }) => {
    await page.goto('/');
    const toggleButton = page.locator('button[aria-label="Toggle dark mode"]');
    const svgPath = toggleButton.locator('svg path');
    
    // Initially shows moon (light mode)
    await expect(toggleButton.locator('svg')).toHaveCount(1);
    let pathD = await svgPath.getAttribute('d');
    expect(pathD).toContain('M21 12.79A9');
    
    // Toggle to dark mode - should show sun
    await toggleButton.click();
    pathD = await svgPath.getAttribute('d');
    expect(pathD).toContain('M16 12a4');
    
    // Toggle back to light mode - should show moon again
    await toggleButton.click();
    pathD = await svgPath.getAttribute('d');
    expect(pathD).toContain('M21 12.79A9');
  });

  test('page content responds to dark mode', async ({ page }) => {
    await page.goto('/');
    const body = page.locator('body');
    
    // Light mode: white background
    await expect(body).toHaveClass(/bg-white/);
    
    // Toggle to dark mode
    const toggleButton = page.locator('button[aria-label="Toggle dark mode"]');
    await toggleButton.click();
    
    // Dark mode: dark background
    await expect(body).toHaveClass(/bg-gray-900/);
  });

  test('about page responds to dark mode', async ({ page }) => {
    await page.goto('/about');
    
    // Toggle to dark mode
    const toggleButton = page.getByRole('button', { name: 'Toggle dark mode' });
    await toggleButton.click();
    
    // Body should have dark background
    await expect(page.locator('body')).toHaveClass(/bg-gray-900/);
  });

  test('docs page cards respond to dark mode', async ({ page }) => {
    await page.goto('/docs');
    
    // Cards have white background in light mode
    const firstCard = page.locator('.grid a').first();
    await expect(firstCard).toHaveClass(/bg-white/);
    
    // Toggle to dark mode
    const toggleButton = page.getByRole('button', { name: 'Toggle dark mode' });
    await toggleButton.click();
    
    // Cards should have dark background in dark mode
    await expect(firstCard).toHaveClass(/bg-gray-800/);
  });
});
